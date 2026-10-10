#pragma once
#include <cstdio>
#ifdef __ANDROID__
#include <android/log.h>
#define IV_LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, "IVNative", __VA_ARGS__); if (iv_log_stderr()) { fprintf(stderr, "[I] " __VA_ARGS__); fputc('\n', stderr); } } while (0)
#define IV_LOGW(...) do { __android_log_print(ANDROID_LOG_WARN, "IVNative", __VA_ARGS__); if (iv_log_stderr()) { fprintf(stderr, "[W] " __VA_ARGS__); fputc('\n', stderr); } } while (0)
#define IV_LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, "IVNative", __VA_ARGS__); if (iv_log_stderr()) { fprintf(stderr, "[E] " __VA_ARGS__); fputc('\n', stderr); } } while (0)
#else
#define IV_LOGI(...) do { fprintf(stderr, "[I] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define IV_LOGW(...) do { fprintf(stderr, "[W] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define IV_LOGE(...) do { fprintf(stderr, "[E] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif
// true for the CLI (mirrors logs to stderr), false inside the app
bool iv_log_stderr();
void iv_set_log_stderr(bool on);
