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

## Current state (2026-10-10 night)

Done on the Mock engine: Stage A (`:core`), B (Room+FTS4, dashboard, detail), C (orchestrator, home/conversation/review/submitted). ~116 JVM/Robolectric tests green; debug APK builds. Pending: D (real mic + Silero VAD wiring on device), E (settings screen, debug overlay polish, emergency banner already in UI), real engine from ML.

## How to build / test (this Mac)

```
export JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd android
~/tools/gradle-8.10.2/bin/gradle :core:test :app:testDebugUnitTest      # all unit tests
~/tools/gradle-8.10.2/bin/gradle :app:assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
```
(The wrapper's download times out on this network; `~/tools/gradle-8.10.2` is a manual copy. Robolectric jars are in `~/tools/robolectric-jars`.)

## Where things are

- `android/core` — pure Kotlin: `Validator`, `TimeResolver`, `LocationMatcher`, `ToolCallParser`, `FormMerger`, `PromptBuilder`, `SopSelector`, `FormMapper`, `Endpointer`, `engine/` (interface + Mock), `orchestrator/` (state machine + ports `AudioInput`, `TtsSpeaker`).
- `android/app` — Compose UI (`ui/`), Room (`data/`), `AppContainer` (DI; swap engine/audio there), `tts/AndroidTtsSpeaker`, `audio/MockAudioInput`.

## Gotchas to remember

- `INTERNET` permission only in a future `planB` flavour (D-54); the single flavour has none.
- FTS is **Room `@Fts4`** with `tok*` query syntax; no `@Upsert` on FTS tables.
- Persist `DEFAULTED` time with `occurred_at_approx=1`; it satisfies the mandatory time field.
- Skip ≠ unknown: "skip" leaves `MISSING`; only "I don't know" gives `UNKNOWN`.
- User edits (`USER_EDITED`) are never overwritten by LLM patches.
- Contract changes (field keys, enums, DTOs) → edit PRD and add a CHANGE.md entry in the same commit.
