// IncidentVoice — Llama 3.2 3B on the Hexagon NPU through Qualcomm Genie (PRD §18.4).
#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <string>

#include "iv_types.h"

namespace iv {

class LlmEngine {
public:
    using TokenFn = std::function<void(const std::string& piece)>;

    ~LlmEngine();

    // bundle_dir holds the AI Hub Genie bundle (genie_config.json, *.bin, tokenizer.json,
    // htp_backend_ext_config.json). Paths inside the config are rewritten to absolute paths.
    // Throws std::runtime_error on failure (message carries the Genie status code).
    void load(const std::string& bundle_dir, int max_tokens_default = 320);
    bool loaded() const { return dialog_ != nullptr; }

    // Runs one independent task: reset dialog → query → collect. sampler: "greedy" | "recommend".
    GenStats generate(const std::string& prompt, const std::string& task, const std::string& sampler,
                      int max_tokens, const TokenFn& on_token, std::string* out_text);

    void abort();
    void release();

    double load_ms() const { return load_ms_; }
    std::string bundle_dir() const { return bundle_dir_; }

private:
    void apply_sampler(const std::string& sampler);

    void* config_ = nullptr;  // GenieDialogConfig_Handle_t
    void* dialog_ = nullptr;  // GenieDialog_Handle_t
    std::string bundle_dir_;
    std::string current_sampler_;
    std::atomic<bool> abort_{false};
    std::mutex mu_;
    double load_ms_ = 0;
};

// Rewrites relative paths in a Genie dialog config to absolute ones under bundle_dir and forces
// a greedy sampler. Exposed for unit tests.
json rewrite_genie_config(const json& cfg, const std::string& bundle_dir);

}  // namespace iv
