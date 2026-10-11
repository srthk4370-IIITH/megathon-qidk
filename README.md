# Qalam: voice-driven incident reports, fully on the Qualcomm QIDK

Speak an incident; get a structured, validated report. Speech recognition (Whisper) and the language model
(Qwen3-4B on the Hexagon NPU) run on the device. The app has no internet permission.

This file covers setup and running only. Design, history and measurements: `HANDBOOK.md`, `eval/RESULTS.md`.

## What you need

- Qualcomm QIDK (SM8750, Android 15) with USB debugging, and `adb` on the laptop.
- JDK 17, Android SDK (platform 35, build-tools), Gradle 8.10 or the wrapper.
- About 4 GB free on the board for the models.

## 1. Build the app

```bash
cd android
./gradlew -PwithAndroid=true :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
```

`-PwithAndroid=true` is required; without it only the pure-Kotlin core is built.

## 2. Install

```bash
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
```

Do not uninstall an existing copy: uninstalling removes the model folder on the board.

## 3. Put the models on the board

Model weights are not in the repository. The app reads them from
`/sdcard/Android/data/com.megathon.incidentvoice/files/models/`.

| Folder | Content | Where it comes from |
|---|---|---|
| `whisper/` | `ggml-small-q5_1.bin` | https://huggingface.co/ggerganov/whisper.cpp (file `ggml-small-q5_1.bin`) |
| `dsp/` | Hexagon V79 skel libraries | Qualcomm AI Runtime (QAIRT) 2.50 SDK, `lib/hexagon-v79/unsigned/`: https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip |
| `llama/` | Qwen3-4B Genie bundle (`genie_config.json`, `tokenizer.json`, `htp_backend_ext_config.json`, `part1_of_4.bin` … `part4_of_4.bin`) and a `model.json` containing `{"family":"qwen","name":"Qwen3-4B"}` | https://huggingface.co/qualcomm/Qwen3-4B → GENIE, w4a16, "Snapdragon 8 Elite For Galaxy Mobile" |

Exact download and `adb push` commands: HANDBOOK, section "On-device LLM: Qwen3-4B on the Hexagon NPU".

Without the `llama/` bundle the app still runs: speech recognition is real and a built-in rules engine fills the
form. With no models at all, Settings → Engine → Mock runs a scripted demo.

## 4. Run

1. Open **Qalam**. The top bar shows a green dot when the engine is ready; the line at the bottom of the screen shows
   the model, tokens per second and bytes sent.
2. Tap the red button (or say "Hey Qalam") and describe the incident, for example: "There was a water leak near
   Conference Room 2 around 10 AM. Maintenance has already isolated the area."
3. Answer the follow-up questions, review, submit. "Type instead" does the same without the microphone.
4. Dashboard: search, filters, hotspots. Ask: questions about past reports. A report's detail screen has
   "Share offline" (QR code).

Settings → "Reset for demo" deletes all reports and loads ten sample reports.

## 5. Tests

```bash
cd android
./gradlew -PwithAndroid=true :core:test :domain:test :app:testDebugUnitTest
```

## 6. Web reader for the QR code

`web/reader.html` is one self-contained page that scans the QR code and makes a PDF; open it from disk, no server
needed. To host it, upload `web/deploy/` to any static host (see `web/README.md`).

## Repository layout

| Path | What |
|---|---|
| `android/app`, `android/domain` | the app (Compose UI, storage, conversation logic, post-processing) |
| `android/core`, `android/engine`, `native/` | shared core library, inference engine and its native sources |
| `config/` | taxonomy, SOPs, site registry, prompts, tool schemas |
| `eval/` | test sets, results, benchmarks |
| `web/` | Qalam Reader |
| `branding/`, `shots/` | logo sources, screenshots from the board |

## Licence

MIT, see `LICENSE`. Third-party models and libraries keep their own licences.
