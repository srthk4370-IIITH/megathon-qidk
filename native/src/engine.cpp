#include "engine.h"

#include <chrono>
#include <stdexcept>
#include <thread>

#include "log.h"

static bool g_log_stderr = false;
bool iv_log_stderr() { return g_log_stderr; }
void iv_set_log_stderr(bool on) { g_log_stderr = on; }

namespace iv {

std::string version_string() { return "iv_native 0.1 (whisper.cpp ASR" + std::string(IV_WITH_GENIE ? " + Genie LLM)" : ", no Genie)"); }

void Engine::init(const EngineConfig& in) {
    cfg_ = in;
    if (cfg_.llm_dir.empty()) cfg_.llm_dir = cfg_.models_dir + "/llama";
    if (cfg_.whisper_model.empty()) cfg_.whisper_model = cfg_.models_dir + "/whisper/ggml-small-q5_1.bin";
    if (cfg_.load_asr) asr_.load(cfg_.whisper_model, cfg_.asr_threads);
#if IV_WITH_GENIE
    if (cfg_.load_llm) {
        std::this_thread::sleep_for(std::chrono::milliseconds(600));
        // Transient DSP failures after boot/idle: retry 3 s / 7 s / 15 s (PRD §31.3).
        const int backoff[] = {0, 3000, 7000, 15000};
        for (int i = 0; i < 4; ++i) {
            if (backoff[i]) std::this_thread::sleep_for(std::chrono::milliseconds(backoff[i]));
            try {
                llm_.load(cfg_.llm_dir, cfg_.llm_max_tokens);
                last_error_.clear();
                break;
            } catch (const std::exception& e) {
                last_error_ = e.what();
                IV_LOGE("LLM load attempt %d failed: %s", i + 1, e.what());
                if (i == 3) throw;
            }
        }
    }
#endif
}

bool Engine::llm_ready() const {
#if IV_WITH_GENIE
    return llm_.loaded();
#else
    return false;
#endif
}

json Engine::info() const {
    json j = {{"plan", cfg_.plan},
              {"version", version_string()},
              {"soc", "SM8750P"},
              {"htp_arch", "v79"},
              {"asr", {{"model", "whisper_small"}, {"backend", "cpu-whispercpp"}, {"path", cfg_.whisper_model}, {"loaded", asr_.loaded()}, {"load_ms", asr_.load_ms()}}}};
#if IV_WITH_GENIE
    j["llm"] = {{"model", "llama_v3_2_3b_instruct"}, {"backend", "npu-genie"}, {"path", cfg_.llm_dir}, {"loaded", llm_.loaded()}, {"load_ms", llm_.load_ms()}, {"context", 4096}};
#else
    j["llm"] = {{"loaded", false}, {"backend", "none"}};
#endif
    if (!last_error_.empty()) j["last_error"] = last_error_;
    return j;
}

AsrResult Engine::transcribe(const std::vector<float>& pcm, LangMode mode) {
    abort_llm();  // ASR has priority over a running background generation (PRD §7.5 rule 2)
    std::lock_guard<std::mutex> lk(npu_);
    return asr_.transcribe(pcm, mode);
}

GenStats Engine::generate(const std::string& prompt, const std::string& task, const std::string& sampler, int max_tokens,
                          const std::function<void(const std::string&)>& on_token, std::string* out_text) {
#if IV_WITH_GENIE
    std::lock_guard<std::mutex> lk(npu_);
    return llm_.generate(prompt, task, sampler, max_tokens, on_token, out_text);
#else
    (void)prompt; (void)task; (void)sampler; (void)max_tokens; (void)on_token; (void)out_text;
    throw std::runtime_error("IV-E601 built without Genie");
#endif
}

void Engine::abort_llm() {
#if IV_WITH_GENIE
    llm_.abort();
#endif
}

void Engine::release() {
    asr_.release();
#if IV_WITH_GENIE
    llm_.release();
#endif
}

}  // namespace iv
