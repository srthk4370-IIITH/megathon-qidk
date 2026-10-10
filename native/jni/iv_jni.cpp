// JNI bridge for com.megathon.incidentvoice.engine.NativeBridge (PRD §18.6, Plan A).
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "engine.h"
#include "log.h"
#include "wav.h"

using iv::json;

namespace {
std::unique_ptr<iv::Engine> g_engine;
std::mutex g_init_mu;
JavaVM* g_vm = nullptr;

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// NewStringUTF needs *modified* UTF-8; go through a Java byte[] → String(UTF-8) for safety with emoji/Devanagari.
jstring to_jstring(JNIEnv* env, const std::string& s) {
    jbyteArray bytes = env->NewByteArray((jsize)s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize)s.size(), reinterpret_cast<const jbyte*>(s.data()));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto out = (jstring)env->NewObject(strCls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    return out;
}

jstring err(JNIEnv* env, const std::exception& e) {
    std::string w = e.what();
    std::string code = w.rfind("IV-E", 0) == 0 ? w.substr(0, 7) : "IV-E600";
    bool retry = code == "IV-E602" || code == "IV-E301";
    return to_jstring(env, iv::EngineError{code, w, retry}.to_json().dump());
}
}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

#define FN(name) Java_com_megathon_incidentvoice_engine_NativeBridge_##name

extern "C" JNIEXPORT jstring JNICALL FN(init)(JNIEnv* env, jobject, jstring modelsDir, jstring nativeLibDir, jstring cfgJson) {
    std::lock_guard<std::mutex> lk(g_init_mu);
    try {
        const std::string models = jstr(env, modelsDir), libdir = jstr(env, nativeLibDir);
        json cfg = json::parse(jstr(env, cfgJson).empty() ? "{}" : jstr(env, cfgJson));
        // Skel libs live in <models>/dsp; stubs in nativeLibraryDir. Semicolon-separated (PRD §18.2).
        const std::string adsp = models + "/dsp;" + libdir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
        setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
        iv::EngineConfig c;
        c.models_dir = models;
        c.asr_threads = cfg.value("asr_threads", 6);
        c.llm_max_tokens = cfg.value("llm_max_tokens", 320);
        if (cfg.contains("whisper_model")) c.whisper_model = models + "/whisper/" + cfg["whisper_model"].get<std::string>();
        c.load_asr = cfg.value("load_asr", true);
        c.load_llm = cfg.value("load_llm", true);
        c.plan = "A";
        auto e = std::make_unique<iv::Engine>();
        e->init(c);
        g_engine = std::move(e);
        return to_jstring(env, g_engine->info().dump());
    } catch (const std::exception& e) {
        IV_LOGE("init failed: %s", e.what());
        return err(env, e);
    }
}

extern "C" JNIEXPORT jstring JNICALL FN(status)(JNIEnv* env, jobject) {
    if (!g_engine) return to_jstring(env, R"({"loaded":false})");
    return to_jstring(env, g_engine->info().dump());
}

extern "C" JNIEXPORT jstring JNICALL FN(asrTranscribe)(JNIEnv* env, jobject, jshortArray pcm, jint sampleRate, jstring langMode) {
    try {
        if (!g_engine) throw std::runtime_error("IV-E202 engine not initialised");
        const jsize n = env->GetArrayLength(pcm);
        std::vector<int16_t> buf(n);
        env->GetShortArrayRegion(pcm, 0, n, reinterpret_cast<jshort*>(buf.data()));
        auto f = iv::pcm16_to_float_16k(buf.data(), buf.size(), sampleRate);
        return to_jstring(env, g_engine->transcribe(f, iv::parse_lang_mode(jstr(env, langMode))).to_json().dump());
    } catch (const std::exception& e) {
        return err(env, e);
    }
}

// cb: object with method `void onToken(String)` (Kotlin fun interface TokenCallback).
extern "C" JNIEXPORT jstring JNICALL FN(llmGenerate)(JNIEnv* env, jobject, jstring prompt, jstring task, jstring sampler, jint maxTokens, jobject cb) {
    try {
        if (!g_engine) throw std::runtime_error("IV-E301 engine not initialised");
        jmethodID onToken = nullptr;
        if (cb) {
            jclass cls = env->GetObjectClass(cb);
            onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cls);
        }
        std::string text;
        auto st = g_engine->generate(
            jstr(env, prompt), jstr(env, task), jstr(env, sampler), maxTokens,
            [&](const std::string& piece) {
                if (!cb || !onToken) return;
                jstring js = to_jstring(env, piece);  // Genie calls back on the calling thread
                env->CallVoidMethod(cb, onToken, js);
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(js);
            },
            &text);
        json out = st.to_json();
        out["text"] = text;
        return to_jstring(env, out.dump());
    } catch (const std::exception& e) {
        return err(env, e);
    }
}

extern "C" JNIEXPORT void JNICALL FN(llmAbort)(JNIEnv*, jobject) {
    if (g_engine) g_engine->abort_llm();
}

extern "C" JNIEXPORT void JNICALL FN(release)(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_init_mu);
    if (g_engine) g_engine->release();
    g_engine.reset();
}
