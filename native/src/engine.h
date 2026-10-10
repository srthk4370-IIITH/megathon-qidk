// IncidentVoice — engine facade used by both the JNI library (Plan A) and iv_cli (Plan B / tests).
#pragma once

#include <memory>
#include <mutex>
#include <string>

#include "asr_engine.h"
#include "iv_types.h"
#if IV_WITH_GENIE
#include "llm_engine.h"
#endif

namespace iv {

struct EngineConfig {
    std::string models_dir;                                  // contains llama/ and whisper/
    std::string llm_dir;                                     // default <models_dir>/llama
    std::string whisper_model;                               // default <models_dir>/whisper/ggml-small-q5_1.bin
    int asr_threads = 6;
    int llm_max_tokens = 320;
    bool load_asr = true;
    bool load_llm = true;
    std::string plan = "A";
};

class Engine {
public:
    // Loads ASR first, then (after a 600 ms gap, PRD §7.5) the LLM. Throws on fatal errors.
    void init(const EngineConfig& cfg);
    json info() const;
    AsrResult transcribe(const std::vector<float>& pcm16k, LangMode mode);
    GenStats generate(const std::string& prompt, const std::string& task, const std::string& sampler, int max_tokens,
                      const std::function<void(const std::string&)>& on_token, std::string* out_text);
    void abort_llm();
    void release();
    bool llm_ready() const;
    bool asr_ready() const { return asr_.loaded(); }

private:
    EngineConfig cfg_;
    AsrEngine asr_;
#if IV_WITH_GENIE
    LlmEngine llm_;
#endif
    std::mutex npu_;  // one model execution at a time (PRD §7.5)
    std::string last_error_;
};

std::string version_string();

}  // namespace iv
