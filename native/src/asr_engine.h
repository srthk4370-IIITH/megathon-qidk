// IncidentVoice — ASR. P0 backend: whisper.cpp (Whisper-small, CPU) per PRD rev 1.1 R1-03.
#pragma once

#include <mutex>
#include <string>
#include <vector>

#include "iv_types.h"

struct whisper_context;

namespace iv {

enum class LangMode { AUTO_EN_HI, FORCE_EN, FORCE_HI };
LangMode parse_lang_mode(const std::string& s);  // "auto"/"auto_en_hi" | "en"/"force_en" | "hi"/"force_hi"

class AsrEngine {
public:
    ~AsrEngine();
    void load(const std::string& model_path, int threads);  // throws std::runtime_error
    bool loaded() const { return ctx_ != nullptr; }
    // pcm: mono float32 in [-1, 1] at 16 kHz.
    AsrResult transcribe(const std::vector<float>& pcm, LangMode mode);

private:
    int run_full(const std::vector<float>& pcm, const char* lang);

public:
    void release();
    double load_ms() const { return load_ms_; }
    std::string model_path() const { return model_path_; }

private:
    whisper_context* ctx_ = nullptr;
    int threads_ = 4;
    std::mutex mu_;
    double load_ms_ = 0;
    std::string model_path_;
};

// Post-processing shared with tests (PRD §16.6 hallucination filter + repetition guard).
std::string clean_transcript(const std::string& raw);

}  // namespace iv
