# handover_app.md — Android app branch (incl. dashboard)

Branch: `renny` (CHANGE.md calls it "Android branch, name TBD"). Spec: `PRD.md` v1.1 (§4.0 overrides older sections). History: `HANDBOOK.md`. Cross-branch changes: `CHANGE.md`.

## Scope of this branch

PRD role P2 plus the in-app dashboard (D-44, app-only):
- Gradle project `android/` with `:core` (pure Kotlin/JVM) and `:app` (Compose) — §20.1, §34.1.
- Conversation state machine (§8), validator (§14), normalisers (§15), parser/prompt builder (§13), emergency detector (§10.7).
- Audio capture + Silero VAD, TTS (eSpeak), Room + `@Fts4` DB (§21, App. E), screens (§20), dashboard (§23).
- Engines behind `InferenceEngine` (§22.1): `MockInferenceEngine` first; real engine consumed from the ML branch.

## Consumed from the ML branch (`Saarthak`)

| Item | Source | State |
|---|---|---|
| `config/taxonomy.json`, `sop.json`, `site_registry.json`, `app_config.json`, `prompts/*` | PRD App. A–C, §22.5 | Not yet committed as files; App branch copies from the PRD until ML commits them |
| `InferenceEngine`, `NativeBridge` (JNI), Plan B HTTP | PRD §22.1, §18.6, §19.2 | Interface defined; real engine pending |
| Tool schemas + DTOs | PRD App. D | Frozen |

## P0 scope (PRD rev 1.1 cuts respected)

In: 30 s narration cap, Silero VAD, whisper.cpp via `transcribe()`, validator loop with TTS (EN + Hinglish templates), time default to `DEFAULTED`, SOP-template recommendations only, review + submit, Room + FTS4, dashboard (recency list, search, category/severity filters, basic detail), emergency banner, settings, debug overlay.

Out (P1+): chunked 3-min narration, draft-resume UI, multi-incident follow-through, status lifecycle beyond SUBMITTED, dashboard stats/charts, PDF/JSON export, prefix cache, LLM-tailored recs, per-field mic, fine-tuning.

## Current state (2026-10-11 01:00)

All P0 app features are built, tested (321 tests) and exercised on an Android 15 arm64 emulator. ML's `:engine` is integrated behind `MlEngineAdapter`. **Never run on the QIDK yet.** Feature-by-feature status: `STATUS.md` → "App branch".

## Module map (after integrating the ML branch)

| Path | Owner | What |
|---|---|---|
| `android/core` | ML (verbatim from `origin/Saarthak`, do not edit here) | ML's pure-Kotlin library; used by app tests as a reference implementation |
| `android/engine` | ML (verbatim) | `InferenceEngine`, `JniInferenceEngine`, prebuilt arm64 libs |
| `android/domain` | App | form model, validator, normalisers, parser, merger, prompts, SOP, **orchestrator** (`orchestrator/`), engine port + mock (`domain/engine/`) |
| `android/app` | App | Compose UI, Room + FTS4, audio (AudioRecord + Silero VAD), TTS, `engineglue/` (adapter + switchable engine), `AppContainer` |
| `config/` | ML (verbatim) | taxonomy, SOPs, prompts, schemas, app_config |

## How to build / test (this Mac)

```
export JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd android
# -PwithAndroid=true is required for :engine, :domain and :app (ML's settings.gradle.kts gate; without it only :core is built)
~/tools/gradle-8.10.2/bin/gradle -PwithAndroid=true :core:test :domain:test :app:testDebugUnitTest   # 190 + 119 + 12
~/tools/gradle-8.10.2/bin/gradle -PwithAndroid=true :app:assembleDebug                               # app/build/outputs/apk/debug/app-debug.apk
```
The wrapper's own download times out on this network (`~/tools/gradle-8.10.2` is a manual copy); Robolectric jars are in `~/tools/robolectric-jars`. Emulator: AVD `iv_test` (`$ANDROID_HOME/emulator/emulator -avd iv_test`).

## Running on the QIDK (board is on the ML machine)

```
bash scripts/app_device_check.sh path/to/app-debug.apk          # full check, Auto engine
MOCK=1 bash scripts/app_device_check.sh path/to/app-debug.apk   # never touches the NPU
```
It checks ABI / page size / clock / eSpeak / `libcdsprpc.so`, installs, grants the mic, starts the app, verifies the native libs were extracted, reports whether models are present and what the engine logged. Then the 2-minute manual pass printed at the end. In the app: Settings → Engine shows the active engine and the exact model folder.

## Gotchas to remember

- Do not edit `android/core`, `android/engine`, `config/`, `android/build.gradle.kts`, `android/gradle.properties` (ML-owned, identical to `Saarthak`). The only shared build file the App touches is `android/settings.gradle.kts` (adds `:domain`, `:app` and the Compose/KSP plugin versions).
- App package for the engine port is `com.megathon.incidentvoice.domain.engine`; ML's is `com.megathon.incidentvoice.engine`. Only `MlEngineAdapter` imports both.
- No `INTERNET` permission (D-54). `extractNativeLibs=true` + legacy jniLibs packaging are required for the QNN stubs.
- FTS is Room `@Fts4` with `tok*` queries; replace FTS rows with delete + insert (no `@Upsert`).
- Edge-to-edge on Android 15: keep `imePadding()` on the root; nested Scaffolds need `WindowInsets(0)`.
- `DEFAULTED` time satisfies the mandatory field (amber in review). Skip ≠ unknown. `USER_EDITED` is never overwritten by the LLM.
- Reference numbers: `INC-yyyyMMdd-NNNN` — never `BASIC_ISO_DATE` on a zoned time.
