#include "asr_engine.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <chrono>
#include <cmath>
#include <regex>
#include <sstream>
#include <stdexcept>

#include "log.h"
#include "whisper.h"

namespace iv {
namespace {
using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) { return std::chrono::duration<double, std::milli>(Clock::now() - t0).count(); }

std::string trim(const std::string& s) {
    size_t a = s.find_first_not_of(" \t\n\r"), b = s.find_last_not_of(" \t\n\r");
    return a == std::string::npos ? "" : s.substr(a, b - a + 1);
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
    return s;
}
}  // namespace

LangMode parse_lang_mode(const std::string& s) {
    if (s == "en" || s == "force_en" || s == "FORCE_EN") return LangMode::FORCE_EN;
    if (s == "hi" || s == "force_hi" || s == "FORCE_HI") return LangMode::FORCE_HI;
    return LangMode::AUTO_EN_HI;
}

std::string clean_transcript(const std::string& raw) {
    std::string t = trim(raw);
    // Known Whisper artefacts on noise/silence.
    static const char* kArtefacts[] = {"thank you for watching", "thanks for watching", "subtitles by",
                                       "please subscribe", "[blank_audio]", "(blank audio)", "[music]", "[silence]"};
    std::string l = lower(t);
    for (const char* a : kArtefacts) {
        size_t p;
        while ((p = l.find(a)) != std::string::npos) {
            t.erase(p, strlen(a));
            l.erase(p, strlen(a));
        }
    }
    // Repetition guard: collapse a 1–6 word phrase repeated 3+ times in a row.
    std::istringstream is(t);
    std::vector<std::string> w;
    for (std::string x; is >> x;) w.push_back(x);
    for (int n = 6; n >= 1; --n) {
        for (size_t i = 0; i + 3 * n <= w.size(); ++i) {
            int reps = 1;
            while (i + (reps + 1) * n <= w.size() &&
                   std::equal(w.begin() + i, w.begin() + i + n, w.begin() + i + reps * n))
                reps++;
            if (reps >= 3) w.erase(w.begin() + i + n, w.begin() + i + reps * n);
        }
    }
    std::string out;
    for (const auto& x : w) out += (out.empty() ? "" : " ") + x;
    if (out.size() < 2) return "";
    return out;
}

AsrEngine::~AsrEngine() { release(); }

void AsrEngine::load(const std::string& model_path, int threads) {
    std::lock_guard<std::mutex> lk(mu_);
    auto t0 = Clock::now();
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    ctx_ = whisper_init_from_file_with_params(model_path.c_str(), cp);
    if (!ctx_) throw std::runtime_error("IV-E202 whisper init failed: " + model_path);
    threads_ = threads;
    model_path_ = model_path;
    load_ms_ = ms_since(t0);
    IV_LOGI("whisper.cpp loaded %s in %.0f ms (threads=%d)", model_path.c_str(), load_ms_, threads);
}

AsrResult AsrEngine::transcribe(const std::vector<float>& pcm, LangMode mode) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!ctx_) throw std::runtime_error("IV-E202 ASR not loaded");
    AsrResult r;
    auto t0 = Clock::now();
    if (pcm.size() < 16000 / 4) {  // < 250 ms: nothing to transcribe
        r.lang = mode == LangMode::FORCE_HI ? "hi" : "en";
        return r;
    }

    std::string lang = mode == LangMode::FORCE_HI ? "hi" : "en";
    if (mode == LangMode::AUTO_EN_HI) {
        // Restrict language ID to en vs hi (PRD §16.6) using the first 30 s.
        auto tm = Clock::now();
        const int n = (int)std::min<size_t>(pcm.size(), 16000 * 30);
        if (whisper_pcm_to_mel(ctx_, pcm.data(), n, threads_) == 0) {
            std::vector<float> probs(whisper_lang_max_id() + 1, 0.f);
            if (whisper_lang_auto_detect(ctx_, 0, threads_, probs.data()) >= 0) {
                const float pe = probs[whisper_lang_id("en")], ph = probs[whisper_lang_id("hi")];
                lang = ph > pe ? "hi" : "en";
            }
        }
        r.mel_ms = ms_since(tm);
    }

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads_;
    p.language = lang.c_str();
    p.translate = false;
    p.no_timestamps = true;
    p.no_context = true;
    p.single_segment = false;
    p.suppress_blank = true;
    p.print_progress = p.print_realtime = p.print_special = p.print_timestamps = false;
    p.temperature_inc = 0.0f;  // no temperature fallback: deterministic + faster
    auto td = Clock::now();
    if (whisper_full(ctx_, p, pcm.data(), (int)pcm.size()) != 0) throw std::runtime_error("IV-E202 whisper_full failed");
    r.dec_ms = ms_since(td);

    std::string all;
    const int ns = whisper_full_n_segments(ctx_);
    for (int i = 0; i < ns; ++i) {
        AsrChunk c;
        c.start_ms = whisper_full_get_segment_t0(ctx_, i) * 10;
        c.end_ms = whisper_full_get_segment_t1(ctx_, i) * 10;
        c.text = trim(whisper_full_get_segment_text(ctx_, i));
        c.lang = lang;
        for (int k = 0; k < whisper_full_n_tokens(ctx_, i); ++k) r.tokens++;
        all += (all.empty() ? "" : " ") + c.text;
        r.chunks.push_back(c);
    }
    r.text = clean_transcript(all);
    r.lang = lang;
    r.total_ms = ms_since(t0);
    return r;
}

void AsrEngine::release() {
    std::lock_guard<std::mutex> lk(mu_);
    if (ctx_) whisper_free(ctx_);
    ctx_ = nullptr;
}

}  // namespace iv
