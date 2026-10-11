// IncidentVoice — Genie wrapper. API usage mirrors Qualcomm's QIDK ASR-LLM-TTS sample (native-lib.cpp).
#include "llm_engine.h"

#include <chrono>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <thread>

#include "GenieCommon.h"
#include "GenieDialog.h"
#if IV_GENIE_HAS_SAMPLER
#include "GenieSampler.h"
#endif

#include "log.h"

namespace iv {
namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

std::string read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read " + path);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

std::string abs_path(const std::string& dir, const std::string& p) {
    if (p.empty() || p[0] == '/') return p;
    return dir + "/" + p;
}

void rewrite_paths(json& node, const std::string& dir) {
    if (node.is_object()) {
        for (auto it = node.begin(); it != node.end(); ++it) {
            const std::string& k = it.key();
            if ((k == "path" || k == "extensions") && it->is_string()) {
                *it = abs_path(dir, it->get<std::string>());
            } else if (k == "ctx-bins" && it->is_array()) {
                for (auto& b : *it)
                    if (b.is_string()) b = abs_path(dir, b.get<std::string>());
            } else {
                rewrite_paths(*it, dir);
            }
        }
    } else if (node.is_array()) {
        for (auto& e : node) rewrite_paths(e, dir);
    }
}

const json kGreedy = {{"version", 1}, {"seed", 42}, {"temp", 1.0}, {"top-k", 1}, {"top-p", 1.0}};
const json kRecommend = {{"version", 1}, {"seed", 42}, {"temp", 0.3}, {"top-k", 40}, {"top-p", 0.95}};

struct QueryCtx {
    const LlmEngine::TokenFn* on_token;
    std::string* text;
    Clock::time_point t0;
    double ttft_ms = -1;
    int pieces = 0;
    int max_tokens = 0;
    std::atomic<bool>* abort;
    bool hit_max = false;
    void* dialog = nullptr;     // for an early stop signalled from the callback
    int depth = 0;              // JSON brace depth of the emitted text (strings skipped)
    bool in_str = false, esc = false, started = false;
    std::atomic<bool> stopped{false};  // we asked Genie to stop (JSON closed or token cap): not an error
};

// Ask Genie to stop from another thread (signalling inside the token callback could deadlock).
void request_stop(QueryCtx* q) {
    if (q->stopped.exchange(true) || !q->dialog) return;
    void* d = q->dialog;
    std::thread([d] { GenieDialog_signal(static_cast<GenieDialog_Handle_t>(d), GENIE_DIALOG_ACTION_ABORT); }).detach();
}

void on_piece(const char* response, const GenieDialog_SentenceCode_t code, const void* user) {
    auto* q = const_cast<QueryCtx*>(static_cast<const QueryCtx*>(user));
    if (!response || code == GENIE_DIALOG_SENTENCE_ABORT) return;
    std::string piece(response);
    if (piece.empty()) return;
    if (q->ttft_ms < 0) q->ttft_ms = ms_since(q->t0);
    q->pieces++;
    if (q->stopped.load()) return;
    if (q->pieces > q->max_tokens || q->abort->load()) {  // cap reached: actually stop generating (was: ignore only)
        q->hit_max = q->pieces > q->max_tokens;
        request_stop(q);
        return;
    }
    q->text->append(piece);
    if (q->on_token && *q->on_token) (*q->on_token)(piece);
    // Tool calls are one JSON object: stop the moment it closes (models may keep talking until the cap).
    static const bool early = !(getenv("IV_JSON_EARLY_STOP") && getenv("IV_JSON_EARLY_STOP")[0] == '0');
    if (!early) return;
    for (char ch : piece) {
        if (q->in_str) { if (q->esc) q->esc = false; else if (ch == '\\') q->esc = true; else if (ch == '"') q->in_str = false; continue; }
        if (ch == '"' && q->started) q->in_str = true;
        else if (ch == '{') { q->depth++; q->started = true; }
        else if (ch == '}' && q->started && --q->depth == 0) { request_stop(q); return; }
    }
}

}  // namespace

json rewrite_genie_config(const json& cfg, const std::string& bundle_dir) {
    json out = cfg;
    rewrite_paths(out, bundle_dir);
    if (out.contains("dialog")) out["dialog"]["sampler"] = kGreedy;
    // "poll": true makes Genie's worker threads busy-spin on the prime cores (cpu-mask 0xe0) even when idle;
    // on the QIDK that slowed whisper.cpp ASR from ~2.5 s to ~22 s per utterance. Interrupt-driven instead
    // (IV_GENIE_POLL=1 restores polling).
    const char* gp = getenv("IV_GENIE_POLL");
    if (!(gp && gp[0] == '1') && out.contains("dialog") && out["dialog"].contains("engine")) {
        auto& be = out["dialog"]["engine"]["backend"];
        if (be.contains("QnnHtp")) be["QnnHtp"]["poll"] = false;
    }
    return out;
}

LlmEngine::~LlmEngine() { release(); }

void LlmEngine::load(const std::string& bundle_dir, int max_tokens_default) {
    std::lock_guard<std::mutex> lk(mu_);
    auto t0 = Clock::now();
    bundle_dir_ = bundle_dir;
    json cfg = rewrite_genie_config(json::parse(read_file(bundle_dir + "/genie_config.json")), bundle_dir);
    const std::string cfg_str = cfg.dump();

    GenieDialogConfig_Handle_t c = nullptr;
    Genie_Status_t st = GenieDialogConfig_createFromJson(cfg_str.c_str(), &c);
    if (st != GENIE_STATUS_SUCCESS || !c)
        throw std::runtime_error("IV-E602 GenieDialogConfig_createFromJson status=" + std::to_string(st));
    GenieDialog_Handle_t d = nullptr;
    st = GenieDialog_create(c, &d);
    if (st != GENIE_STATUS_SUCCESS || !d) {
        GenieDialogConfig_free(c);
        throw std::runtime_error("IV-E602 GenieDialog_create status=" + std::to_string(st));
    }
    st = GenieDialog_setMaxNumTokens(d, static_cast<uint32_t>(max_tokens_default));
    if (st != GENIE_STATUS_SUCCESS) IV_LOGW("GenieDialog_setMaxNumTokens status=%d", st);
    config_ = const_cast<void*>(static_cast<const void*>(c));
    dialog_ = const_cast<void*>(static_cast<const void*>(d));
    current_sampler_ = "greedy";
    load_ms_ = ms_since(t0);
    IV_LOGI("Genie loaded from %s in %.0f ms", bundle_dir.c_str(), load_ms_);
}

void LlmEngine::apply_sampler(const std::string& sampler) {
    if (sampler == current_sampler_) return;
#if IV_GENIE_HAS_SAMPLER
    GenieSampler_Handle_t s = nullptr;
    if (GenieDialog_getSampler(static_cast<GenieDialog_Handle_t>(dialog_), &s) == GENIE_STATUS_SUCCESS && s) {
        const std::string js = (sampler == "recommend" ? kRecommend : kGreedy).dump();
        GenieSamplerConfig_Handle_t sc = nullptr;
        if (GenieSamplerConfig_createFromJson(js.c_str(), &sc) == GENIE_STATUS_SUCCESS && sc) {
            if (GenieSampler_applyConfig(s, sc) == GENIE_STATUS_SUCCESS) current_sampler_ = sampler;
            GenieSamplerConfig_free(sc);
        }
    }
    if (current_sampler_ != sampler) IV_LOGW("sampler %s not applied; staying %s", sampler.c_str(), current_sampler_.c_str());
#else
    IV_LOGW("Genie sampler API unavailable; using greedy for %s", sampler.c_str());
#endif
}

GenStats LlmEngine::generate(const std::string& prompt, const std::string& task, const std::string& sampler,
                             int max_tokens, const TokenFn& on_token, std::string* out_text) {
    std::lock_guard<std::mutex> lk(mu_);
    GenStats gs;
    gs.task = task;
    if (!dialog_) throw std::runtime_error("IV-E301 LLM not loaded");
    auto* d = static_cast<GenieDialog_Handle_t>(dialog_);
    abort_ = false;
    GenieDialog_reset(d);  // tasks are independent (PRD §18.4)
    apply_sampler(sampler);
    GenieDialog_setMaxNumTokens(d, static_cast<uint32_t>(max_tokens));

    std::string text;
    QueryCtx q{&on_token, &text, Clock::now(), -1, 0, max_tokens, &abort_, false};
    q.dialog = const_cast<void*>(static_cast<const void*>(d));
    Genie_Status_t st = GenieDialog_query(d, prompt.c_str(), GENIE_DIALOG_SENTENCE_COMPLETE, on_piece, &q);
    gs.wall_ms = ms_since(q.t0);
    gs.tokens_out = q.pieces;
    gs.ttft_ms = q.ttft_ms < 0 ? gs.wall_ms : q.ttft_ms;
    const double dec_ms = gs.wall_ms - gs.ttft_ms;
    gs.decode_tps = (q.pieces > 1 && dec_ms > 0) ? (q.pieces - 1) * 1000.0 / dec_ms : 0;
    gs.aborted = abort_.load();
    if (st != GENIE_STATUS_SUCCESS && !gs.aborted && !q.stopped.load()) {
        gs.stop = "ERROR";
        GenieDialog_reset(d);
        throw std::runtime_error("IV-E301 GenieDialog_query status=" + std::to_string(st));
    }
    gs.stop = gs.aborted ? "ABORT" : (q.hit_max || q.pieces >= max_tokens ? "MAX_TOKENS" : "EOS");
    if (q.pieces == 0 && !gs.aborted)  // empty output is an engine problem, never a content result (PRD §13.7)
        throw std::runtime_error("IV-E301 empty LLM output");
    if (out_text) *out_text = text;
    return gs;
}

void LlmEngine::abort() {
    abort_ = true;
#if IV_GENIE_HAS_SIGNAL
    if (dialog_) GenieDialog_signal(static_cast<GenieDialog_Handle_t>(dialog_), GENIE_DIALOG_ACTION_ABORT);
#endif
}

void LlmEngine::release() {
    std::lock_guard<std::mutex> lk(mu_);
    if (dialog_) GenieDialog_free(static_cast<GenieDialog_Handle_t>(dialog_));
    if (config_) GenieDialogConfig_free(static_cast<GenieDialogConfig_Handle_t>(config_));
    dialog_ = config_ = nullptr;
}

}  // namespace iv
