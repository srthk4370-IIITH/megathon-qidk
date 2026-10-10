#include "wav.h"

#include <algorithm>
#include <cstring>
#include <iterator>
#include <fstream>
#include <stdexcept>

namespace iv {
namespace {
std::vector<float> resample(const std::vector<float>& in, int sr) {
    if (sr == 16000 || in.empty()) return in;
    const double ratio = 16000.0 / sr;
    const size_t n = (size_t)(in.size() * ratio);
    std::vector<float> out(n);
    for (size_t i = 0; i < n; ++i) {
        const double src = i / ratio;
        const size_t a = (size_t)src;
        const size_t b = std::min(a + 1, in.size() - 1);
        const double f = src - a;
        out[i] = (float)(in[a] * (1 - f) + in[b] * f);
    }
    return out;
}
}  // namespace

std::vector<float> pcm16_to_float_16k(const int16_t* data, size_t n, int sample_rate) {
    std::vector<float> f(n);
    for (size_t i = 0; i < n; ++i) f[i] = data[i] / 32768.0f;
    return resample(f, sample_rate);
}

std::vector<float> read_wav_16k_mono(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("cannot open wav " + path);
    std::vector<char> buf((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (buf.size() < 44 || std::memcmp(buf.data(), "RIFF", 4) || std::memcmp(buf.data() + 8, "WAVE", 4))
        throw std::runtime_error("not a RIFF/WAVE file: " + path);
    uint16_t fmt = 0, ch = 0, bits = 0;
    uint32_t sr = 0;
    size_t pos = 12;
    const char* data = nullptr;
    uint32_t data_len = 0;
    while (pos + 8 <= buf.size()) {
        uint32_t len;
        std::memcpy(&len, buf.data() + pos + 4, 4);
        const char* id = buf.data() + pos;
        const char* body = buf.data() + pos + 8;
        if (!std::memcmp(id, "fmt ", 4)) {
            std::memcpy(&fmt, body, 2);
            std::memcpy(&ch, body + 2, 2);
            std::memcpy(&sr, body + 4, 4);
            std::memcpy(&bits, body + 14, 2);
        } else if (!std::memcmp(id, "data", 4)) {
            data = body;
            data_len = (uint32_t)std::min<size_t>(len, buf.size() - (pos + 8));
            break;
        }
        pos += 8 + len + (len & 1);
    }
    if (!data || !ch || !sr) throw std::runtime_error("wav missing fmt/data chunk");
    std::vector<float> mono;
    if (fmt == 1 && bits == 16) {
        const size_t frames = data_len / (2 * ch);
        mono.resize(frames);
        for (size_t i = 0; i < frames; ++i) {
            float acc = 0;
            for (int c = 0; c < ch; ++c) {
                int16_t s;
                std::memcpy(&s, data + (i * ch + c) * 2, 2);
                acc += s / 32768.0f;
            }
            mono[i] = acc / ch;
        }
    } else if (fmt == 3 && bits == 32) {
        const size_t frames = data_len / (4 * ch);
        mono.resize(frames);
        for (size_t i = 0; i < frames; ++i) {
            float acc = 0;
            for (int c = 0; c < ch; ++c) {
                float s;
                std::memcpy(&s, data + (i * ch + c) * 4, 4);
                acc += s;
            }
            mono[i] = acc / ch;
        }
    } else {
        throw std::runtime_error("unsupported wav format (need PCM16 or float32)");
    }
    return resample(mono, (int)sr);
}
}  // namespace iv
