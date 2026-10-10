// iv_cli — on-device inference CLI (contract: native/IV_CLI.md).
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <set>
#include <sstream>
#include <string>

#include "engine.h"
#include "log.h"
#include "wav.h"

using iv::json;

namespace {

std::map<std::string, std::string> parse_args(int argc, char** argv, int from) {
    std::map<std::string, std::string> a;
    for (int i = from; i < argc; ++i) {
        std::string k = argv[i];
        if (k.rfind("--", 0) != 0) continue;
        k = k.substr(2);
        if (i + 1 < argc && std::string(argv[i + 1]).rfind("--", 0) != 0) a[k] = argv[++i];
        else a[k] = "1";
    }
    return a;
}

std::string get(const std::map<std::string, std::string>& a, const std::string& k, const std::string& d) {
    auto it = a.find(k);
    return it == a.end() ? d : it->second;
}

std::string read_file(const std::string& p) {
    std::ifstream f(p, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read " + p);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

void emit(const json& j) {
    std::cout << j.dump() << "\n";
    std::cout.flush();
}

int fail(const std::string& what, int rc = 1) {
    std::string code = what.rfind("IV-E", 0) == 0 ? what.substr(0, 7) : "IV-E600";
    const bool retryable = code == "IV-E602" || code == "IV-E301";
    emit(iv::EngineError{code, what, retryable}.to_json());
    return retryable ? 2 : rc;
}

iv::EngineConfig make_cfg(const std::map<std::string, std::string>& a, bool asr, bool llm) {
    iv::EngineConfig c;
    c.models_dir = get(a, "models", "/data/local/tmp/iv/models");
    c.llm_dir = get(a, "llm-dir", "");
    c.whisper_model = get(a, "whisper-model", "");
    c.asr_threads = std::stoi(get(a, "threads", "6"));
    c.llm_max_tokens = std::stoi(get(a, "max-tokens", "320"));
    c.load_asr = asr;
    c.load_llm = llm;
    c.plan = "B";
    return c;
}

int cmd_serve(iv::Engine& eng) {
    // JSON-lines worker, PRD §19.3.
    std::string line;
    bool inited = false;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        json req;
        try { req = json::parse(line); } catch (...) { emit({{"ok", false}, {"error", "bad json"}}); continue; }
        const auto id = req.value("id", json(nullptr));
        const std::string op = req.value("op", "");
        try {
            if (op == "init") {
                iv::EngineConfig c;
                c.models_dir = req.value("models", "/data/local/tmp/iv/models");
                c.asr_threads = req.value("threads", 6);
                c.plan = "B";
                eng.init(c);
                inited = true;
                emit({{"id", id}, {"ok", true}, {"info", eng.info()}});
            } else if (op == "status") {
                emit({{"id", id}, {"ok", true}, {"info", eng.info()}, {"inited", inited}});
            } else if (op == "asr") {
                auto pcm = req.contains("wav_path") ? iv::read_wav_16k_mono(req["wav_path"].get<std::string>()) : std::vector<float>();
                if (req.contains("pcm_path")) {
                    std::string raw = read_file(req["pcm_path"].get<std::string>());
                    pcm = iv::pcm16_to_float_16k(reinterpret_cast<const int16_t*>(raw.data()), raw.size() / 2, req.value("sr", 16000));
                }
                auto r = eng.transcribe(pcm, iv::parse_lang_mode(req.value("lang_mode", "auto")));
                emit({{"id", id}, {"ok", true}, {"result", r.to_json()}});
            } else if (op == "generate") {
                std::string prompt = req.contains("prompt_path") ? read_file(req["prompt_path"].get<std::string>()) : req.value("prompt", "");
                std::string text;
                auto st = eng.generate(prompt, req.value("task", "GENERATE"), req.value("sampler", "greedy"), req.value("max_tokens", 320),
                                       [&](const std::string& p) { emit({{"id", id}, {"tok", p}}); }, &text);
                emit({{"id", id}, {"done", true}, {"text", text}, {"stats", st.to_json()}});
            } else if (op == "abort") {
                eng.abort_llm();
                emit({{"id", id}, {"ok", true}});
            } else if (op == "shutdown") {
                emit({{"id", id}, {"ok", true}});
                break;
            } else {
                emit({{"id", id}, {"ok", false}, {"error", "unknown op " + op}});
            }
        } catch (const std::exception& e) {
            emit({{"id", id}, {"ok", false}, {"error", iv::EngineError{std::string(e.what()).substr(0, 7), e.what(), true}.to_json()["error"]}});
        }
    }
    eng.release();
    return 0;
}

int cmd_llm_batch(iv::Engine& eng, const std::map<std::string, std::string>& a) {
    const std::string in = get(a, "in", ""), out = get(a, "out", "");
    std::set<std::string> done;
    {
        std::ifstream f(out);
        for (std::string l; std::getline(f, l);) {
            try { done.insert(json::parse(l).value("id", "")); } catch (...) {}
        }
    }
    std::ifstream fin(in);
    std::ofstream fout(out, std::ios::app);
    int n = 0, skipped = 0, errors = 0;
    for (std::string l; std::getline(fin, l);) {
        if (l.empty()) continue;
        json r = json::parse(l);
        const std::string id = r.value("id", std::to_string(n));
        if (done.count(id)) { skipped++; continue; }
        json res = {{"id", id}};
        try {
            std::string text;
            auto st = eng.generate(r.value("prompt", ""), r.value("task", "EXTRACT"), r.value("sampler", get(a, "sampler", "greedy")),
                                   r.value("max_tokens", std::stoi(get(a, "max-tokens", "320"))), nullptr, &text);
            res["text"] = text;
            res["stats"] = st.to_json();
        } catch (const std::exception& e) {
            res["error"] = e.what();
            errors++;
        }
        fout << res.dump() << "\n";
        fout.flush();
        n++;
        fprintf(stderr, "[batch] %d done (%d skipped, %d errors) id=%s\n", n, skipped, errors, id.c_str());
    }
    emit({{"done", true}, {"processed", n}, {"skipped", skipped}, {"errors", errors}});
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    iv_set_log_stderr(true);
    if (argc < 2) {
        fprintf(stderr, "usage: iv_cli info|asr|llm|llm-batch|serve [--options]  (see native/IV_CLI.md)\n");
        return 1;
    }
    const std::string cmd = argv[1];
    auto a = parse_args(argc, argv, 2);
    iv::Engine eng;
    try {
        if (cmd == "serve") return cmd_serve(eng);
        if (cmd == "info") {
            eng.init(make_cfg(a, !a.count("no-asr"), !a.count("no-llm")));
            emit(eng.info());
            return 0;
        }
        if (cmd == "asr") {
            eng.init(make_cfg(a, true, false));
            auto pcm = iv::read_wav_16k_mono(get(a, "wav", ""));
            emit(eng.transcribe(pcm, iv::parse_lang_mode(get(a, "lang", "auto"))).to_json());
            return 0;
        }
        if (cmd == "llm") {
            eng.init(make_cfg(a, false, true));
            const bool stream = a.count("stream");
            std::string text;
            auto st = eng.generate(read_file(get(a, "prompt-file", "")), get(a, "task", "GENERATE"), get(a, "sampler", "greedy"),
                                   std::stoi(get(a, "max-tokens", "320")),
                                   stream ? std::function<void(const std::string&)>([](const std::string& p) { emit({{"tok", p}}); }) : nullptr,
                                   &text);
            emit({{"done", true}, {"text", text}, {"stats", st.to_json()}});
            return 0;
        }
        if (cmd == "llm-batch") {
            eng.init(make_cfg(a, false, true));
            return cmd_llm_batch(eng, a);
        }
        fprintf(stderr, "unknown command %s\n", cmd.c_str());
        return 1;
    } catch (const std::exception& e) {
        return fail(e.what());
    }
}
