// IncidentVoice native core — shared DTOs (PRD Appendix D.5).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "nlohmann/json.hpp"

namespace iv {

using json = nlohmann::json;

struct AsrChunk {
    int64_t start_ms = 0;
    int64_t end_ms = 0;
    std::string text;
    std::string lang;
};

struct AsrResult {
    std::string text;
    std::string lang;
    std::vector<AsrChunk> chunks;
    double mel_ms = 0, enc_ms = 0, dec_ms = 0, total_ms = 0;
    int tokens = 0;
    std::string backend = "cpu-whispercpp";

    json to_json() const {
        json c = json::array();
        for (const auto& ch : chunks)
            c.push_back({{"start_ms", ch.start_ms}, {"end_ms", ch.end_ms}, {"text", ch.text}, {"lang", ch.lang}});
        return {{"text", text},
                {"lang", lang},
                {"chunks", c},
                {"backend", backend},
                {"timings", {{"mel_ms", mel_ms}, {"enc_ms", enc_ms}, {"dec_ms", dec_ms}, {"tokens", tokens}, {"total_ms", total_ms}}}};
    }
};

struct GenStats {
    std::string task;
    int tokens_in = -1;   // -1 = not reported by the runtime
    int tokens_out = 0;
    double ttft_ms = 0;
    double prefill_tps = 0;
    double decode_tps = 0;
    double wall_ms = 0;
    bool aborted = false;
    std::string stop = "EOS";  // EOS | MAX_TOKENS | ABORT | ERROR

    json to_json() const {
        return {{"task", task},           {"tokens_in", tokens_in},   {"tokens_out", tokens_out},
                {"ttft_ms", ttft_ms},     {"prefill_tps", prefill_tps}, {"decode_tps", decode_tps},
                {"wall_ms", wall_ms},     {"aborted", aborted},       {"stop", stop}};
    }
};

struct EngineError {
    std::string code;
    std::string message;
    bool retryable = false;
    json to_json() const { return {{"error", {{"code", code}, {"message", message}, {"retryable", retryable}}}}; }
};

}  // namespace iv
