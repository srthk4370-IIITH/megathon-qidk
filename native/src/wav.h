#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace iv {
// Reads a PCM16/float32 WAV (any rate/channels) → mono float32 at 16 kHz (linear resample). Throws on error.
std::vector<float> read_wav_16k_mono(const std::string& path);
// PCM16 → float, resampled to 16 kHz.
std::vector<float> pcm16_to_float_16k(const int16_t* data, size_t n, int sample_rate);
}  // namespace iv
