# IncidentVoice — Product Requirements & Engineering Specification (PRD)

**Project:** AI Incident Report Assistant — Megathon X × Qualcomm (Problem Statement 1)
**Product name:** IncidentVoice (Android package `com.megathon.incidentvoice`)
**Target hardware:** Qualcomm QIDK, **Snapdragon 8 Elite (SM8750P), Hexagon HTP V79**, Android 15 userdebug
**Document status:** v1.0 — the single source of truth. Anything that contradicts this document is wrong until this document is updated.
**Date:** 2026-10-10
**Team:** 3 people (P1 ML/NPU, P2 Android, P3 "Brain"), 15-hour hackathon, nothing may be built before the clock starts.

---

## How to use this document

- **§1–§5** explain *what* we build and *why*: problem, judging, decisions, scope. Read these first. (20 min)
- **§6–§9** explain *how the system is put together*: hardware facts, architecture, flow, latency. Everyone reads these.
- **§10–§15** define the *domain*: incident types, fields, questions, SOPs, LLM contract, validator, normalisers. P3 owns these; P2 and P1 must follow them exactly.
- **§16–§24** are *component specs*: ASR, TTS, native layer, Plan B server, Android app, database, APIs, dashboard, export.
- **§25–§28** are *model work*: acquiring, exporting, deploying and fine-tuning the models.
- **§29–§33** are *quality*: evaluation, tests, error handling, security, performance operations.
- **§34–§38** are *execution*: repo layout, the 15-hour plan, risks, optional features, demo script.
- **Appendices** hold complete config files, complete prompts, error→fix tables, and command cheat sheets.

**Conventions.**
- `MUST` / `SHOULD` / `MAY` are used in the RFC 2119 sense.
- Priorities: **P0** = MVP, demo fails without it. **P1** = planned, do after all P0 is green. **P2** = only if time remains. **O-x** = optional features, designed but not committed (§37).
- Honesty markers (inherited from `problems_setup.md`): `[verified]` observed by us, `[measured-AIHub]` from Qualcomm's published `perf.yaml` for Galaxy S25 (same SoC as ours), `[inferred]` best reading, `[verify]` must be checked on the board at the hour stated.
- Every cross-reference is written as §N.M. Field names are always written in `code` and are identical in config files, prompts, Kotlin, C++, SQL and evaluation.
- Times are in IST (Asia/Kolkata) in the UI; stored as UTC epoch milliseconds.

---

## Table of contents

0. Glossary
1. Executive summary
2. Problem statement analysis and judging criteria mapping
3. Answers to the open points in RA.txt
4. Decision log (D-01 … D-64)
5. Scope and priorities
6. Environment facts (board, laptop, versions)
7. System architecture
8. End-to-end flow and the conversation state machine
9. Latency budget and NPU optimisation
10. Incident taxonomy: common fields, categories, category fields, severity, emergencies
11. Follow-up question bank
12. SOP / recommendation library
13. LLM contract: tool calling, prompts, parsing, grounding
14. Validator specification
15. Normalisers: time, location, people, language
16. ASR subsystem (audio → text)
17. TTS subsystem
18. Native layer (C++ core, JNI, Genie, QNN Whisper)
19. Plan B: shell-user inference server (Go) and adb loopback
20. Android application
21. Data model and storage
22. Interfaces and endpoints
23. Dashboard
24. Report export (PDF / JSON)
25. Model acquisition and export
26. Device setup and deployment
27. Fine-tuning the LLM
28. Fine-tuning Whisper (stretch)
29. Evaluation plan
30. Testing strategy
31. Error handling and resilience
32. Security and privacy
33. Performance, thermal and memory operations
34. Repository structure and conventions
35. The 15-hour execution plan
36. Risk register
37. Optional features (designed, not committed)
38. Demo script
- Appendix A — `config/taxonomy.json` (complete)
- Appendix B — `config/sop.json` (complete)
- Appendix C — Prompts (complete)
- Appendix D — JSON Schemas for tool calls
- Appendix E — SQL DDL (complete)
- Appendix F — Error → cause → fix (V79 edition)
- Appendix G — Command cheat sheet
- Appendix H — Laptop and board housekeeping already done
- Appendix I — References

---

## 0. Glossary

| Term | Meaning in this project |
|---|---|
| **QIDK** | Qualcomm Innovators Development Kit. Ours: SM8750P (Snapdragon 8 Elite), serial `a8d3cefb`. |
| **HTP / NPU** | Hexagon Tensor Processor, the NPU inside the Hexagon DSP. Arch **V79** on our board. |
| **QAIRT** | Qualcomm AI Runtime SDK (formerly QNN SDK). Supplies `libGenie.so`, `libQnnHtp.so`, stub/skel libraries, headers, `genie-t2t-run`. We standardise on **2.50**. |
| **Stub / Skel** | `libQnnHtpV79Stub.so` runs on the ARM CPU and marshals calls; `libQnnHtpV79Skel.so` runs on the DSP. Skel is found through `ADSP_LIBRARY_PATH` (semicolon-separated). |
| **Context binary** | A `.bin` file holding a compiled, quantised graph for one DSP arch. Not portable across arches. |
| **Genie** | Qualcomm's LLM runtime on top of QNN (tokeniser, KV cache, sampler, dialog). C API `GenieDialog_*`; CLI `genie-t2t-run`. |
| **Genie bundle** | Context binaries + `genie_config.json` + `htp_backend_ext_config.json` + `tokenizer.json`, produced by AI Hub with `--target-runtime genie`. |
| **AI Hub** | Qualcomm's free cloud compiler (`qai-hub`, `qai-hub-models`). Compiles our models for V79. |
| **w4a16** | 4-bit weights, 16-bit integer activations (static encodings). The precision we use for Llama. |
| **Plan A** | Inference runs **inside the app process** via JNI (Genie C API + QNN API). Preferred. |
| **Plan B** | Inference runs in a **shell-user process** (Go server + C++ worker) reached over `127.0.0.1`. Fallback if the app sandbox is refused the NPU (error 14001). |
| **InferenceEngine** | The single Kotlin interface the app uses for `transcribe()` and `generate()`. Plan A and Plan B are two implementations of it (§22.1). |
| **Narration** | The user's first free-form spoken description of the incident. |
| **Turn** | One unit of conversation: narration, a follow-up question, an answer, or a correction. |
| **Common field** | A field present in every incident (§10.2). |
| **Category field** | A field that exists only for one incident category (§10.4). |
| **Mandatory field** | A field the validator requires before the report can be submitted (§14). |
| **Field status** | One of `MISSING`, `FILLED`, `INFERRED`, `UNKNOWN`, `USER_EDITED`, `DEFAULTED` (§14.2). |
| **SOP** | Standard Operating Procedure step from `config/sop.json`; recommendations are selected and tailored from these (§12). |
| **ERT** | Emergency Response Team phone number configured on the device (default `112`). |
| **Hinglish** | Hindi–English code-mixed speech, usually written in Roman script ("pipe se paani leak ho raha tha"). |
| **BOS** | `<|begin_of_text|>` (id 128000). Genie adds it itself — our prompts MUST NOT contain it (§13.9). |
| **VAD** | Voice activity detection — decides when speech starts/ends (Silero VAD v5, §16.4). |
| **FTS5** | SQLite full-text search extension used for dashboard keyword search (§21.4). |

---

## 1. Executive summary

IncidentVoice lets a worker report an incident by **speaking naturally** (English or Hinglish) to a Qualcomm QIDK. Everything runs **on-device, offline, on the Snapdragon NPU**:

```
 SPEAK ──► UNDERSTAND ──► EXTRACT ──► VALIDATE ──► ASK (voice) ──► RECOMMEND ──► REVIEW ──► SUBMIT ──► DASHBOARD
  mic      Whisper-small   Llama 3.2 3B   rules in     Android TTS     Llama 3.2 3B   editable    SQLite      search,
  + VAD    on HTP (V79)    tool call on   Kotlin       + auto-mic      + SOP library  form        + FTS5      filters,
                           HTP (Genie)                  loop ≤3 rounds                                       stats
```

**The core loop (P0):**
1. User taps the mic and speaks for up to 3 minutes. VAD ends recording after 1.2 s of silence.
2. **Whisper-small** (AI Hub, fp16, QNN context binaries, HTP V79) transcribes. The transcript appears on screen and can be edited.
3. **Llama 3.2 3B Instruct** (AI Hub Genie bundle, w4a16) reads the transcript and emits **one function call** `fill_incident_form{...}`: it normalises Hinglish to English, classifies the incident into one of **10 categories** (+ `OTHER`), and extracts common and category-specific fields.
4. A **deterministic validator** (no model) checks the mandatory fields for that category.
5. If something is missing, the app **asks by voice** (templated questions, up to 2 related fields per question, max 3 rounds), listens to the answer, and Llama emits `update_incident_fields{...}` to patch the form.
6. When complete, Llama (same loaded model, different prompt) selects and tailors **recommended follow-up actions** from a curated SOP list via `propose_actions{...}`.
7. The user reviews an editable form (per-field voice correction available), then submits.
8. The report is stored in a local **SQLite + FTS5** database with the audio, transcript and audit log; the **dashboard** lists incidents by recency with keyword search, filters and summary stats; reports can be exported as **PDF** or **JSON**.
9. Critical incidents (fire, unconscious person, gas leak …) instantly show a **red emergency banner** with one-tap dial to the ERT, before the form is even complete.

**Why this design wins on the judging criteria** (§2.3): every AI model runs on the Hexagon NPU; natural conversational Hinglish is handled by a multilingual ASR and an LLM that normalises; extraction is measurable (per-field F1 on a 150-case test set + 50 recorded clips); mapping to report fields is enforced by a JSON schema and a deterministic validator; function calling is real (Llama 3.2 JSON tool calls executed by the app).

**Latency target:** ≤ 8 s from end-of-speech to a filled form for a 20-second narration, with fields appearing progressively (§9). Measured AI Hub figures for our SoC: Llama 3.2 3B w4a16 ~28 tok/s decode and ~730 tok/s prefill; Whisper-small encoder ~66 ms, decoder ~8 ms/token `[measured-AIHub]`.

---

## 2. Problem statement analysis and judging criteria mapping

### 2.1 What the slides say (verbatim essentials)

From `Problem Statement.pptx` (5 slides + speaker notes):

- **Title:** "AI Incident Report Assistant — End-to-end voice-driven incident reporting using on-device Generative AI and function calling."
- **Pipeline diagram (slide 3):** Speech-to-Text → LLM Information Extraction → *Mandatory fields present?* (decision) → Function/Tool calling → Populate Form / User Review / Submit Report → Display on Screen and Text-to-Speech output.
- **Example fields:** Incident Type, Severity Level, Incident Location, Date & Time, Persons Involved, Equipment / Asset Involved, Incident Description, Immediate Actions Taken, Witness Information, Recommended Follow-Up Action.
- **Example input:** "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."
- **Example output:** Incident Type → Water Leak; Location → Conference Room 2; Time → 10 AM; Action Taken → Area Isolated.
- **Evaluation criteria:**
  1. All AI models must execute on-device using Snapdragon acceleration. Strictly no cloud APIs.
  2. Ability to understand natural conversational speech rather than predefined commands.
  3. Accuracy of speech recognition and transcription.
  4. Accuracy of extracting incident information from user narration.
  5. Correct mapping of extracted information to report fields.
- **Slide 5 tagline:** SPEAK → UNDERSTAND → EXTRACT → REVIEW → SUBMIT; "Natural conversation • Accurate extraction • On-device execution".
- **Resources:** QIDK docs, QIDK GitHub (`qidk/` folder in this repo), Qualcomm AI Hub, QAIRT SDK setup guide.

### 2.2 Reading between the lines

| Slide element | Our interpretation | Where implemented |
|---|---|---|
| "Mandatory fields present?" diamond | A **deterministic** check, then a loop back to the user. The diagram's TTS output is the voice channel for this loop. | §14 validator, §11 questions, §17 TTS |
| "Function/Tool calling" box | The LLM must emit a structured call that the app executes to populate the form. | §13 tool calling |
| "Populate Form / User Review / Submit Report" | Human-in-the-loop review is required; nothing is submitted without the user. | §20.6 review screen |
| "Display on Screen" + "Text-to-Speech output" | Two output channels: visual form and spoken prompts. | §20, §17 |
| "Recommended Follow-Up Action" field | Generated by the system, not by the reporter. | §12 |
| Example output "Area Isolated" from "Maintenance has already isolated the area" | Paraphrased/normalised extraction, not verbatim span copying. | §13.5 rule 2 |
| "Strictly no cloud APIs" | No network at runtime at all. App ships without `INTERNET` permission in Plan A. | §32 |

### 2.3 Judging criteria → evidence we will show

| # | Criterion | Our evidence (shown in demo + slides) |
|---|---|---|
| 1 | On-device, Snapdragon-accelerated | Whisper + Llama run on HTP V79 (QNN/Genie). Debug overlay shows backend = HTP, tok/s, ms per stage. Airplane mode during the demo. TTS/VAD are tiny CPU components — we say so openly. |
| 2 | Natural conversational speech | Hinglish + English narrations, filler words, self-corrections ("no wait, it was Room 3"), relative times ("kal raat", "an hour ago"). |
| 3 | ASR accuracy | WER/CER on 50 recorded clips (English + Hinglish) shown on a slide (§29). |
| 4 | Extraction accuracy | Incident-type accuracy and per-field F1 on 150 text cases + the 50 clips (§29). |
| 5 | Correct field mapping | Strict JSON schema, enum normalisation, validator; per-field mapping accuracy reported separately from extraction (§29.3). |

---

## 3. Answers to the open points in RA.txt

`RA.txt` was a list of points to research. Each point and its resolution:

| RA.txt point | Resolution |
|---|---|
| "Once a speech is inputted, the model transcribes it, then translates (filters it) and gives proper English output." | Whisper-small transcribes (raw, may be Roman Hinglish or Devanagari). **Translation/normalisation to English happens inside the single Llama extraction call** — every extracted value is written in clear English (D-14). We do not run a separate translation call because it would add a full LLM pass (~2–4 s) per turn. The raw transcript is kept for evidence and search. |
| "Understand the type of incident." | Llama classifies into one primary + up to 2 secondary categories from a fixed list of 10 + `OTHER` (§10.3). |
| "Fill the form (see the questions for form)." | Llama emits `fill_incident_form` with common fields (§10.2) and category fields (§10.4) in the same call. |
| "Give the output to validator, it validates if all the questions are answered." | Deterministic Kotlin validator (§14), driven by `config/taxonomy.json`. Not a model, exactly as RA.txt says. |
| "If not then go to text to speech and ask for missing fields." | Templated questions (EN/Hinglish), ≤2 related fields per question, ≤3 rounds; Android TTS; mic auto-starts after the question (§11, §17). |
| "If yes, go to another model and get recommended steps." | Same loaded Llama bundle, **different prompt/role** (D-28). Loading a second LLM on the NPU costs seconds and risks `err 1002` on bundle switching (`problems_setup.md` §5.1). Recommendations are SOP-grounded (§12). |
| "(Optional) Speak out the recommended steps." | O-1, §37. |
| "Ingest all the data to database locally." | Room + bundled SQLite with FTS5, app-private storage (§21). |
| "(Optional: conversational interface to get data locally)" | O-2, §37. |
| "Dashboard with all incidents recency-based, searchable keyword-wise." | §23: recency list, FTS5 keyword search, filters, stats. |
| "(Optional: cloud data and conversational management with alerts)" | O-3, §37. |
| "Define types of incidents." | §10.3 and Appendix A. |
| "Define questions regarding each type of incident." | §10.4 (fields) and §11 (spoken questions) and Appendix A. |
| "Models: Whisper-base (fine-tune for Hinglish), Llama 3.2 3B instruct for reasoning and validator, TTS Android built-in or SuperTonic." | **Changed:** Whisper-**small** instead of base (better on accented/Hinglish/noisy speech; still ~66 ms encoder on V79, D-11). Llama 3.2 3B kept for extraction + recommendations; the **validator is rule-based**, not Llama (RA.txt's own clarification). TTS = Android `TextToSpeech` API; on this board the only installed engine is **eSpeak NG** (`com.reecedunn.espeak`) `[verified]` (§17). Fine-tuning both models is a **stretch goal** gated at hour 12 (D-49, D-50). |
| "Incident type is a more general type that only serves to fetch the kind of questions we need; validator does the rest." | Exactly our design: category → field set and question set from `taxonomy.json` → validator decides what to ask. |
| "The validator is not model based … the model is only trained to extract information and put it regarding the corresponding question." | Exactly our design. The model's only jobs are: extract (`fill_incident_form`), patch (`update_incident_fields`), recommend (`propose_actions`). |
| "Conversational interface will have vector database right? But …" (unfinished) | **Not necessarily.** For "show me all fire incidents in Building B last week", structured filters + FTS5 answer it better than vectors: Llama converts the question into a `search_incidents{filters}` tool call, SQLite answers. A vector index (`sqlite-vec` in the same DB file + a small CPU embedding model) is only worth adding for fuzzy "find similar incidents" queries. Full design note in §37 (O-2). No questions were asked about optional features, per instructions. |

---

## 4. Decision log

Every decision below came from the clarification round (60 questions) or from measurements on the board. IDs are referenced throughout the document. Changing a decision means updating this table *and* every section that references the ID.

| ID | Decision | Source |
|---|---|---|
| D-01 | Board is **SM8750P / Snapdragon 8 Elite / HTP V79**, Android 15 userdebug, 15.5 GB RAM. AI Hub device target **"Samsung Galaxy S25 (Family)"** (also the qai-hub-models default). | adb inventory `[verified]` |
| D-02 | Use whatever root/build approach is best → we use userdebug + `adb root` for setup only; architecture decided by the probe (D-39). | Q |
| D-03 | Training compute: laptop RTX 4050 6 GB + Kaggle (T4/P100, 30 GB RAM). | Q |
| D-04 | Hackathon: **15 hours, 3 people in parallel, nothing built before the clock.** | Q |
| D-05 | Board cleanup: wipe `/data/local/tmp` (done: 29 GB free), keep installed apps. | Q, executed |
| D-06 | LLM: **stock Llama 3.2 3B Instruct** bundle first (exported at hour 0); fine-tuned bundle is a stretch swap. | Q |
| D-07 | Laptop cleanup (done) + WSL VHDX compaction by user (admin command, Appendix H). | Q, executed |
| D-08 | Whisper: **stock AI Hub Whisper-small on NPU**; Hinglish fine-tune as stretch. | Q |
| D-09 | Team split: P1 ML/NPU, P2 Android, P3 Brain (§35). | Q |
| D-10 | Languages: **English + Hinglish (Roman code-mix)**; Hindi Devanagari tolerated as ASR output. | Q |
| D-11 | ASR model: **whisper_small** (float/fp16, `qnn_context_binary`). | Q |
| D-12 | Capture: tap-to-start, auto-stop on 1.2 s silence (VAD), manual stop button. | Q |
| D-13 | Max narration **3 minutes**, chunked into ≤28 s windows at silences, transcribed while recording. | Q |
| D-14 | Hinglish→English normalisation happens **inside the single extraction call**. | Q |
| D-15 | Transcript shown, editable, auto-proceed after 2 s of no edits. | Q |
| D-16 | Environment is **noisy industrial/outdoor** → Android NoiseSuppressor + high-pass + Silero VAD; stricter VAD thresholds. | Q |
| D-17 | **Store raw audio** with each incident. | Q |
| D-18 | Domain: **Workplace EHS — industrial plant + office/campus.** | Q |
| D-19 | **10 categories + OTHER.** | Q |
| D-20 | **One primary + up to 2 secondary** types; mandatory set = union. | Q |
| D-21 | **Near miss is a flag** (`near_miss`), not a category. | Q |
| D-22 | Severity: **LOW / MEDIUM / HIGH / CRITICAL** with rubric; LLM proposes, rules can raise, user can override. | Q |
| D-23 | Time: LLM extracts the raw phrase; **deterministic resolver** converts to ISO with `approximate` flag. | Q |
| D-24 | Location: free text + **fuzzy match to a site registry**. | Q |
| D-25 | Reporter identity from a **device profile** (name + employee ID). | Q |
| D-26 | Mandatory common fields: **type, description, location, occurred_at, severity, injuries**. Time is asked if missing (not defaulted). | Q |
| D-27 | Follow-ups: **≤2 related fields per spoken question**, templated (no LLM to generate questions). | Q |
| D-28 | Max **3 follow-up rounds**, then remaining gaps are highlighted for manual entry; submit blocked until filled or marked Unknown. | Q |
| D-29 | "I don't know"/"pata nahi" → field status **UNKNOWN**, counts as answered. | Q |
| D-30 | Follow-up answers use **targeted patch extraction** (`update_incident_fields`). | Q |
| D-31 | **One LLM call** for classify + extract (common + category fields). | Q |
| D-32 | Function calling = **Llama 3.2 JSON tool calls**, executed by the app. | Q |
| D-33 | Sampler: **greedy** (top-k 1) for extract/patch; **temp 0.3** for recommendations. | Q |
| D-34 | Recommendations: **curated SOP checklist per category + LLM selects/tailors**. | Q |
| D-35 | Recommendations use the **same Llama bundle**, different prompt. | Q |
| D-36 | Recommendations generated **after validation passes, before review**, streamed; critical incidents show template steps instantly. | Q |
| D-37 | TTS: **Android TextToSpeech API** (engine on board: eSpeak NG). | Q, `[verified]` |
| D-38 | Spoken questions **match the user's language** (EN or Roman Hinglish). | Q |
| D-39 | Architecture: **probe first; Plan A (in-app JNI) if NPU reachable, else Plan B (shell-user backend).** | Q |
| D-40 | After TTS, **mic auto-starts**; typing is the fallback; no barge-in. | Q |
| D-41 | Review: **editable form + per-field mic** for voice correction. | Q |
| D-42 | Outputs: **local DB always + PDF export + JSON export**. | Q |
| D-43 | DB: **SQLite + FTS5** (Room 2.7 with bundled SQLite driver). | Q |
| D-44 | Dashboard lives **in the Android app** on the QIDK screen. | Q |
| D-45 | Dashboard MVP: recency list + keyword search, filters (category/severity/status/date), summary stats. A basic detail view is implied (P0); audio playback in detail is P1. | Q |
| D-46 | Status lifecycle **DRAFT → SUBMITTED → ACKNOWLEDGED → RESOLVED → CLOSED**, audit-logged. | Q |
| D-47 | UI: **Kotlin + Jetpack Compose + Room + Gradle**. | Q |
| D-48 | Plan B backend: **Go** static binary (front/supervisor) + our C++ worker (warm models). | Q |
| D-49 | LLM fine-tune data: **synthetic (Claude/GPT, offline build-time) + hand-written seeds + team recordings**. | Q |
| D-50 | Whisper fine-tune data: **public Hinglish/code-switch corpora + our recorded clips** (stretch). | Q |
| D-51 | Inference boundary: app owns DB, UI, validator, TTS, VAD, resolvers; **engine is inference-only** (stateless). | Q |
| D-52 | Latency target **≤ 8 s** end-of-speech → filled form for a 20 s narration, progressive UI. | Q |
| D-53 | **Whisper + Llama both resident**; NPU calls serialised by a mutex. | Q |
| D-54 | **Fully offline**, no INTERNET permission in Plan A, DB in app-private storage. | Q |
| D-55 | Whisper implementation: **AI Hub context binaries + our own C++ decode loop** (JNI in Plan A, CLI worker in Plan B). | Q |
| D-56 | Two incidents in one narration: **detect, handle the first, offer to start the second.** | Q |
| D-57 | Critical incidents: **instant banner + emergency steps + one-tap dial** (ERT/112). | Q |
| D-58 | Evaluation: **50 recorded clips + 150 text cases**; WER, type accuracy, per-field F1, latency. | Q |
| D-59 | Monorepo; branch names to be assigned by the team later (§34). | Q |
| D-60 | Work split: P1 ML/NPU, P2 Android, P3 Brain. | Q |
| D-61 | Nothing built before the clock → all exports start at **hour 0** (Llama export on **Kaggle** to avoid laptop RAM/disk/uplink limits). | Q |
| D-62 | Android: **minSdk 31, targetSdk 35, arm64-v8a only, QIDK only.** | Q |
| D-63 | **QAIRT 2.50** downloaded at hour 0; all compiles and device libs at 2.50 (board's leftover libs are 2.47 `[verified]`, too old for 2.50-compiled binaries). | Q, `[verified]` |
| D-64 | Fine-tune gate: LLM QLoRA starts ~hour 4; fine-tuned bundle replaces stock **only if device eval beats it by ≥5 points by hour 12**. | Q |

---

## 5. Scope and priorities

### 5.1 In scope (P0 — the MVP that must demo)

| ID | Capability |
|---|---|
| F-01 | Voice capture with VAD auto-stop and manual stop; up to 3 min. |
| F-02 | Whisper-small ASR on HTP; transcript displayed and editable. |
| F-03 | Llama 3.2 3B on HTP: classification + extraction via `fill_incident_form`. |
| F-04 | Deterministic validator using `taxonomy.json`. |
| F-05 | Spoken follow-up questions (TTS), auto-listening, patch extraction via `update_incident_fields`; ≤3 rounds; UNKNOWN handling. |
| F-06 | Time resolver and location fuzzy matcher. |
| F-07 | Recommendations via `propose_actions` grounded in `sop.json`. |
| F-08 | Review screen: editable fields, required-field highlighting, submit. |
| F-09 | Local DB (Room + FTS5), drafts autosaved. |
| F-10 | Dashboard: recency list, keyword search, filters, summary stats, basic detail view, status changes. |
| F-11 | Emergency banner (keyword + severity), one-tap dial. |
| F-12 | Settings: reporter profile, ERT number, engine status. |
| F-13 | Debug overlay: per-stage timings, tok/s, backend. |
| F-14 | Device setup scripts + smoke tests. |

### 5.2 Planned (P1)

| ID | Capability |
|---|---|
| F-20 | Per-field mic correction in review. |
| F-21 | PDF and JSON export via share sheet. |
| F-22 | Audio playback in detail view; audit log view. |
| F-23 | Genie system-prompt KV prefix cache (save/restore) for faster calls (§9.4). |
| F-24 | Multi-incident detection follow-through (D-56). |
| F-25 | Eval runner screen on device (§29.5). |
| F-26 | Fine-tuned Llama bundle swap (gate D-64). |
| F-27 | Plan B full laptop-free launch via adbd loopback (only if Plan B is chosen). |

### 5.3 Stretch (P2)

| ID | Capability |
|---|---|
| F-30 | Fine-tuned Hinglish Whisper. |
| F-31 | Better TTS voice (Sherpa-ONNX/Piper TTS engine APK; no code change, §17.5). |
| F-32 | Audio transcoding to AAC after submit. |
| F-33 | Site registry editor in settings. |

### 5.4 Optional (O-x, designed but not committed, §37)

- **O-1** Speak out the recommended steps.
- **O-2** Conversational query over the local incident DB.
- **O-3** Cloud sync and alerting.

### 5.5 Explicitly out of scope

- Multi-user login/roles, assignment workflows, SLA timers.
- Any runtime network call (cloud LLM, cloud ASR, telemetry).
- Phones other than the QIDK (D-62).
- Languages beyond English/Hinglish (Hindi Devanagari is tolerated, not optimised).
- Image/video evidence capture.

---

## 6. Environment facts

### 6.1 The board (measured 2026-10-10 over adb) `[verified]`

| Property | Value | Consequence |
|---|---|---|
| adb serial | `a8d3cefb` (`product:sun model:Sun_for_arm64`) | Use `-s a8d3cefb` if more than one device is attached. |
| SoC | `ro.soc.model = SM8750P` (Snapdragon 8 Elite) | **HTP V79.** Use `libQnnHtpV79Stub.so` / `libQnnHtpV79Skel.so`. AI Hub device "Samsung Galaxy S25 (Family)". |
| `soc_model` (for `htp_backend_ext_config.json`) | **69** for SM8750 `[verify H1]` — read it from the AI Hub-generated `htp_backend_ext_config.json`; never hand-write it. | A wrong `soc_model` fails context creation. |
| OS | Android 15 (`AQ3A.240801.002`), `userdebug`, `test-keys`, `ro.debuggable=1` | `adb root` available for setup. |
| adb auth | `ro.adb.secure` unset → no RSA prompt | Plan B loopback trivial; also a security risk (§32.3). |
| SELinux | Enforcing | Don't rely on permissive. |
| FastRPC nodes | `/dev/fastrpc-cdsp` (`crw-rw-r-- system system`), `/dev/fastrpc-cdsp-secure`, `/dev/fastrpc-adsp-secure` | Unlike the 8 Gen 3 board in `problems_setup.md`, there is a cdsp FastRPC node — consistent with apps being able to use the NPU. Still must be probed from a real APK (§26.6). |
| Vendor public libs | `libadsprpc.so`, `libcdsprpc.so`, `libsdsprpc.so` in `/vendor/etc/public.libraries.txt` | App must declare `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>` (targetSdk ≥31). |
| RAM | 15.5 GB total, ~13 GB available | Whisper-small (~1 GB) + Llama 3B (~3.5 GB) resident is fine (D-53). |
| `/data` | 45 GB, **29 GB free after cleanup** | Enough for both models + runtime + DB. |
| Clock | **Board date was `2024-08-14`**, timezone **GMT** `[verified]` | MUST set correct date and `Asia/Kolkata` at setup (§26.3); the time resolver depends on the device clock. |
| Thermal zones | `nsphvx-0..2`, `nsphmx-0..3` (NPU), `cpu-*`, `gpuss-*`, `sys-therm-*` | Thermal guard (§33.2). |
| TTS engines | only `com.reecedunn.espeak` (eSpeak NG) | D-37: use `TextToSpeech` API with eSpeak. |
| Leftover runtime | QAIRT **2.47** V79 libs + `genie-t2t-run` (backed up to `_board_runtime_backup/`) | Too old for AI Hub's 2.50 compiles; use only as an emergency fallback for 2.45-compiled models (§25.6). |
| Installed 3rd-party apps | `com.qualcomm.qti.bert`, `com.example.esw`, `com.google.ai.edge.gallery`, `com.qidk.fastvlm(.test)`, `com.example.fastvlmlite`, `com.reecedunn.espeak`, `com.qcom.imagesuperres` | Kept (D-05). eSpeak is needed. |

### 6.2 The laptop `[verified]`

| Property | Value | Consequence |
|---|---|---|
| OS | Windows 11 Home 26200 | Use PowerShell or Git Bash with `MSYS_NO_PATHCONV=1` for adb (§Appendix G). |
| GPU | RTX 4050 Laptop, 6 GB | QLoRA 3B possible but tight; Whisper fine-tune OK; prompt iteration with 4-bit Llama OK. |
| RAM | 15.6 GB; WSL limited to 9 GB + 64 GB swap (`.wslconfig`) | Llama export locally is swap-heavy → do it on Kaggle (D-61). |
| C: free | ~14 GB after cleanup; **~120 GB after the user runs the VHDX compaction** (Appendix H) | WSL swap file and export temp live on C:. |
| WSL | Ubuntu (ext4.vhdx 162 GB logical, 43 GB used), `~/venvs/qaihm` (qai-hub-models **0.63.0**, qai-hub 0.56.0), `~/venvs/train` (torch 2.5.1+cu124, transformers 4.46.3, peft 0.13.2, bitsandbytes 0.44.1), AI Hub token configured | Reuse these venvs. |
| HF cache (WSL) | `meta-llama/Llama-3.2-3B-Instruct` (6 GB) already downloaded | No re-download for fine-tuning on the laptop. |
| adb | `C:\Users\hp\Desktop\qidk\Code\platform-tools\adb.exe` (single copy) | Hard-code this path in scripts (`scripts/env.ps1`). |
| Android Studio / NDK / JDK | **Not installed** | Hour-0 install (§26.1). |
| QAIRT SDK | **Not present** | Hour-0 download of 2.50 (§25.1). |

### 6.3 Version matrix (pin these)

| Component | Version | Notes |
|---|---|---|
| QAIRT SDK (host + device libs) | **2.50.x** (AI Hub's current default; S25 perf data was produced with `2.50.0.260828221209`) | Device libs MUST be ≥ compile version (`problems_setup.md` A5). |
| qai-hub-models | **0.63.0** | Pins torch/transformers for the published Llama encodings. Do not upgrade mid-hackathon. |
| qai-hub | 0.56.0 | |
| Llama | `meta-llama/Llama-3.2-3B-Instruct`, w4a16, CL 4096, AR128 + AR1 graphs, 3 parts | Gated on HF: `HF_TOKEN` required. |
| Whisper | `openai/whisper-small` via AI Hub `whisper_small`, precision float (fp16 on HTP), `qnn_context_binary`, 2 components (encoder, decoder) | Max decoded length 200 tokens per 30 s window. |
| Android Studio | Ladybug (2024.2) or newer | |
| AGP / Gradle | AGP 8.7.x / Gradle 8.10+ | |
| Kotlin | 2.0.x (K2) with Compose compiler plugin | |
| Compose BOM | 2024.12.01 or newer | |
| Room | **2.7.x** with `androidx.sqlite:sqlite-bundled` (FTS5) | |
| NDK | r27c | `ANDROID_STL=c++_shared`. |
| CMake | 3.22.1 | |
| JDK | 17 | |
| ONNX Runtime Android | 1.20.x (CPU only, for Silero VAD) | |
| Silero VAD | v5 ONNX (16 kHz) | ~2 MB. |
| Go | 1.22+ (`GOOS=linux GOARCH=arm64 CGO_ENABLED=0`) | Plan B only. |
| Python (host) | 3.11 (qaihm venv), 3.12 (train venv) | |

### 6.4 Physical hardware notes (from `qidk guide.pdf`)

The guide in the repo is Qualcomm's **HDK8650 (SM8650P) Quick Start Guide** (80-61511-1 Rev AA). Our board reports **SM8750P**, so treat connector positions as orientation only `[verify by looking at the board]`; the functions below are common to these HDK main boards.

| Item | What the guide says | How we use it |
|---|---|---|
| Package | Main board, **12 V DC adapter**, USB cable, **2 speakers**; display/camera/sensor/GNSS cards and battery optional | Always power from the 12 V adapter (§33.3). Connect both speakers — TTS output for the follow-up questions. |
| Microphones | On-board **MIC1/5** (bottom) and **MIC3/4** (top) | Default capture path. |
| **3.5 mm headset jack** | Analog headset by default (DIP `S5801-8` OFF) | **Recommended for the demo hall:** a wired headset/lapel mic is far closer to the mouth than the on-board mics — the single cheapest ASR accuracy win in noise (D-16). Android routes `VOICE_RECOGNITION` to the wired mic automatically when plugged. |
| **HDMI output** | Turn **ON `S5702-7` and `S5702-8`** (DSI0/DSI1 → HDMI) **before powering on** | Mirror the app to the projector/monitor for judges. If the board has a display card, HDMI replaces the panel output while these switches are ON. |
| Emergency download | `S5801-1` (FORCED_USB_BOOT) ON before power-on, USB-C | **Do not touch** — reflashing is out of scope. All DIP switches default OFF except S8101-1/4/5/6/7 (12 V DC mode: S8101-1/4/7 ON, 2/3/8 OFF). |
| Power key / volume keys | Power key on the bottom; Volume +/− on top | Volume keys adjust TTS loudness during the demo. |
| USB | USB Type-C (adb) and Micro-USB | Use the Type-C port directly on the laptop (no hub); the previous board dropped off the bus on hubs/long cables. |
| Fan connector | Present on the main board | Attach a fan if available — the NPU reached 94 °C on the previous board (§33.2). |

---
## 7. System architecture

### 7.1 Component overview

```
┌──────────────────────────────── IncidentVoice APK (Kotlin, Compose) ─────────────────────────────────┐
│                                                                                                       │
│  UI layer (Compose)                                                                                   │
│   HomeScreen ─ ConversationScreen ─ ReviewScreen ─ DashboardScreen ─ DetailScreen ─ SettingsScreen   │
│        │                 ▲                                                                            │
│        ▼                 │ StateFlow<ConversationUiState>                                             │
│  ConversationViewModel ──┴──► ConversationOrchestrator (state machine, §8.2)                         │
│                                  │        │          │            │             │                     │
│                     AudioCapture │  Validator   PromptBuilder  ToolCallParser  Normalisers          │
│                     + SileroVAD  │  (§14)       (§13.4)        (§13.6)         (time/location §15)   │
│                     (§16)        │                                                                    │
│                                  ▼                                                                    │
│                       TtsSpeaker (§17)        IncidentRepository (§21) ── Room + FTS5 (bundled SQLite)│
│                                  │                                                                    │
│                                  ▼                                                                    │
│                    InferenceEngine (interface, §22.1)                                                 │
│        ┌────────────────────────┼─────────────────────────────┐                                       │
│        ▼                        ▼                             ▼                                       │
│  JniInferenceEngine      HttpInferenceEngine           MockInferenceEngine                            │
│  (Plan A)                (Plan B)                      (dev / tests)                                  │
│        │                        │ http://127.0.0.1:8765                                               │
└────────┼────────────────────────┼─────────────────────────────────────────────────────────────────────┘
         │ JNI                    │
         ▼                        ▼
  libiv_native.so          iv-server (Go, uid shell) ──stdin/stdout JSON-lines──► iv_npud (C++ worker, uid shell)
  (same C++ core)                                                                     (same C++ core)
         │                                                                                  │
         └──────────────┬───────────────────────────────────────────────────────────────────┘
                        ▼
        C++ core (native/):  NpuScheduler (mutex) ─┬─ WhisperEngine (QNN API: encoder + decoder ctx bins, mel, BPE decode)
                                                   └─ LlmEngine (Genie C API: GenieDialog, sampler configs, abort)
                        │
                        ▼
        libQnnHtp.so / libQnnHtpV79Stub.so (ARM)  ──FastRPC──►  libQnnHtpV79Skel.so (Hexagon HTP V79)
```

**Key property:** the C++ core is **one codebase** compiled twice: as `libiv_native.so` (JNI, Plan A) and as the `iv_npud` executable (Plan B). The Kotlin app is identical in both plans except for which `InferenceEngine` implementation is injected. This is what lets P2 build the whole app on day one against `MockInferenceEngine` while P1 is still bringing up the NPU.

### 7.2 Responsibilities (who owns what, who may not)

| Component | Owns | Must NOT |
|---|---|---|
| UI (Compose) | Rendering, user input, navigation | Call the engine directly; hold business rules |
| `ConversationOrchestrator` | Conversation state machine, turn sequencing, timeouts, retries | Talk to SQLite directly (goes through repository) |
| `AudioCapture` + `SileroVad` | Mic, preprocessing, end-of-speech, chunking | Know about incidents |
| `Validator` | Mandatory-field logic, next question selection | Call any model |
| `PromptBuilder` | Rendering prompt templates from `config/prompts/*.txt` | Contain prompt text inline (single source = config files) |
| `ToolCallParser` | Lenient JSON parsing, schema validation, enum normalisation | Invent values |
| `Normalisers` | Time resolution, location match, person/equipment cleanup | Call models |
| `IncidentRepository` | All DB reads/writes, FTS index maintenance, audit log | Know about UI |
| `InferenceEngine` impls | `transcribe`, `generate`, `abort`, `status`, `warmup` | Hold incident state, build prompts, touch the DB (D-51) |
| C++ core | NPU access, model lifetime, mutex, mel, decode loops | Know about incidents or prompts |
| `TtsSpeaker` | Speaking questions, completion callbacks | Decide what to ask |

### 7.3 Plan A vs Plan B

| Aspect | Plan A (in-app, preferred) | Plan B (shell-user backend, fallback) |
|---|---|---|
| Where models run | App process, via JNI | `iv_npud` process, uid `shell` |
| How app reaches NPU | Direct (`libcdsprpc.so` via vendor public libs) | HTTP to `127.0.0.1:8765` (Go `iv-server`) |
| Model files | `/sdcard/Android/data/com.megathon.incidentvoice/files/models/` | `/data/local/tmp/iv/models/` |
| Warm models | Yes (load once at app start) | Yes (worker loads once at server start) |
| Started by | App launch | Laptop (`scripts/planb_start.ps1`) for MVP; adbd loopback from app for laptop-free (F-27) |
| Permissions | RECORD_AUDIO; **no INTERNET** | RECORD_AUDIO + INTERNET (loopback sockets need it) |
| Chosen when | Probe APK creates the HTP device successfully (§26.6) | Probe fails with `14001` / `open_device_node ... Permission denied` |
| Evidence it will work | Qualcomm's own `qidk/GenAI-Solutions/ASR-LLM-TTS` sample runs Genie + Whisper **in-app on SM8750 (V79)**; board exposes `/dev/fastrpc-cdsp` | Proven on the 8 Gen 3 board (`problems_setup.md` A10/A12) |

Probe result recorded at hour ~6 in `docs/probe_result.md` and in §4 as D-39a. Until then both are built against the same interface; Plan B's extra code is small (Go server ~300 lines, worker `main()` ~150 lines) because the C++ core is shared.

> **CLI probe already attempted (2026-10-10):** running `qnn-platform-validator` via `run-as` failed with "Missing shared libraries: libcdsprpc.so" — expected, because executables launched with `run-as` do not get the app linker namespace that exposes vendor public libraries. **This is inconclusive**, not a failure. Only an APK proves Plan A.

### 7.4 Process and thread model (Plan A)

| Thread / dispatcher | Work | Notes |
|---|---|---|
| Main | Compose UI | Never blocks. |
| `audioThread` (dedicated, `THREAD_PRIORITY_URGENT_AUDIO`) | `AudioRecord.read`, preprocessing, ring buffer | 20 ms frames. |
| `vadDispatcher` (`Dispatchers.Default.limitedParallelism(1)`) | Silero VAD inference on CPU (ORT, 1 thread) | ~1 ms per 32 ms window. |
| `npuDispatcher` (`newSingleThreadContext("npu")`) | **All** JNI calls into the C++ core | Serialises NPU work at the Kotlin level; the C++ `NpuScheduler` mutex is the second line of defence. |
| `Dispatchers.IO` | Room, file writes, audio WAV writes, exports | |
| TTS engine thread | eSpeak (separate process/service) | Callbacks via `UtteranceProgressListener`. |

### 7.5 NPU scheduling rules (non-negotiable)

These come from measured failures on the previous board (`problems_setup.md` §5.3) and apply to both plans:

1. **Exactly one NPU execution at a time.** Whisper encoder, Whisper decoder steps and Genie queries never overlap. Enforced by `npuDispatcher` + C++ `std::mutex`.
2. **Priority:** ASR chunk > extraction/patch > recommendation. A pending recommendation generation is **aborted** (`GenieDialog_signal(ABORT)`) if an ASR request arrives (e.g. the user taps a per-field mic while recommendations stream). It is re-run afterwards.
3. **Never load/unload bundles during a session.** Both models are loaded once at startup, in the order Whisper → (0.6 s gap) → Llama, and stay resident (D-53).
4. **Never run two engine processes.** Plan B: one `iv_npud`; the Go server refuses to spawn a second one and kills orphans on start.
5. On startup failure with `err 1002`/`5005` (transient after boot/idle): retry with back-off **3 s, 7 s, 15 s** (§31.3). Report as hardware failure, never as a content failure.

### 7.6 Memory budget (board, Plan A)

| Item | Estimate | Source |
|---|---|---|
| Llama 3.2 3B w4a16 Genie session | 3.2–3.7 GB RSS | `problems_setup.md` §5.3 `[verified on V75]` |
| Whisper-small encoder + decoder (fp16) context + buffers | ~1.0 GB | AI Hub sizes 391 + 533 MB float `[measured-AIHub]` |
| App heap + Compose + Room | ~0.3 GB | `[inferred]` |
| ORT + Silero | ~0.05 GB | |
| **Total** | **~5 GB** of ~13 GB available | Comfortable. |

### 7.7 Storage budget (board)

| Item | Size |
|---|---|
| Llama bundle (3 `.bin` + tokenizer + configs) | ~2.7 GB |
| Whisper-small context binaries + vocab + mel filters | ~1.0 GB |
| QAIRT 2.50 runtime (ARM + V79 DSP libs) | ~0.15 GB |
| APK | ~40 MB (ORT + native libs) |
| Audio per incident (WAV 16 kHz mono) | ~1.9 MB/min → 3 min = 5.8 MB |
| DB | < 50 MB for thousands of incidents |

---

## 8. End-to-end flow and the conversation state machine

### 8.1 Happy path sequence (narration complete on first try)

```
User         App/Orchestrator          VAD/Audio        Engine(Whisper)    Engine(Llama)      Validator   TTS   Repository
 │ tap mic ──►│ state=LISTENING ──────►│ start         │                  │                  │           │      │ createDraft()
 │ speaks ... │                         │ chunk1 (28s)──► transcribe(c1) ─►│ (NPU while user   │           │      │
 │            │                         │               │   still talks)   │                  │           │      │
 │ silence ── │ ◄── endOfSpeech ────────│               │                  │                  │           │      │
 │            │ state=TRANSCRIBING ─────────────────────► transcribe(last)│                  │           │      │
 │            │ ◄── transcript ─────────────────────────│                  │                  │           │      │ saveTurn(NARRATION)
 │            │ emergencyKeywordScan(transcript) → banner if hit (§10.7)  │                  │           │      │
 │            │ state=TRANSCRIPT_REVIEW (2 s auto-proceed, editable)      │                  │           │      │
 │            │ state=EXTRACTING ───────────────────────────────────────── generate(extract) │           │      │
 │            │ ◄──── streamed tokens → incremental parse → fields appear ─│                  │           │      │
 │            │ parse+normalise+merge ─────────────────────────────────────────────────────►│ validate  │      │ saveForm()
 │            │ ◄───────────────────────────────────────────────── complete=true ──────────│           │      │
 │            │ state=RECOMMENDING ─────────────────────────────────────── generate(recommend, temp .3) │      │
 │            │ state=REVIEW (recs stream into review screen)              │                  │           │      │
 │ edits/OK ─►│ submit_incident_report() ────────────────────────────────────────────────────────────────────►│ submit()
 │            │ state=SUBMITTED → "Report INC-20261010-0007 saved" → offer second incident if detected (D-56)
```

### 8.2 States

| State | Entered when | UI | Exits to |
|---|---|---|---|
| `IDLE` | App start / after submit / cancel | Big mic button, recent incidents preview | `LISTENING` |
| `LISTENING` | Mic tapped or auto-started after TTS | Pulsing mic, live level meter, elapsed time, Stop button, "Type instead" | `TRANSCRIBING` (end of speech / stop), `IDLE` (cancel), `ERROR_AUDIO` |
| `TRANSCRIBING` | End of speech | Spinner "Transcribing…" with chunk progress | `TRANSCRIPT_REVIEW`, `ERROR_ENGINE` |
| `TRANSCRIPT_REVIEW` | Transcript available | Editable transcript, 2 s countdown ring, "Continue" | `EXTRACTING` (timeout/continue), `LISTENING` (re-record) |
| `EXTRACTING` | Narration confirmed | Form skeleton; fields fill as tokens stream | `VALIDATING`, `ERROR_PARSE`, `ERROR_ENGINE` |
| `VALIDATING` | Tool call parsed and merged | (instant) | `ASKING` (missing & rounds<3), `RECOMMENDING` (complete), `MANUAL_FILL` (missing & rounds=3) |
| `ASKING` | Validator selected a question | Question bubble, TTS speaking, fields highlighted | `LISTENING` (TTS done → auto mic), `TYPING` |
| `TYPING` | User taps "Type instead" | Text field | `PATCHING` |
| `PATCHING` | Answer transcript ready | Highlighted fields animate | `VALIDATING` |
| `MANUAL_FILL` | 3 rounds exhausted | Review screen with red required fields | `RECOMMENDING` (once all required are filled/UNKNOWN) |
| `RECOMMENDING` | Validation complete | Review screen; "Recommended actions" streaming | `REVIEW` |
| `REVIEW` | Recommendations done (or aborted) | Full editable form, Submit | `SUBMITTED`, `FIELD_VOICE_EDIT`, `IDLE` (discard → DRAFT kept) |
| `FIELD_VOICE_EDIT` | Per-field mic tapped (P1) | Mini listening sheet | `PATCHING` (targeted to that field) → back to `REVIEW` |
| `SUBMITTED` | Submit succeeded | Confirmation with ref no., PDF/JSON share, "Report another" | `IDLE`, `LISTENING` (second incident) |
| `ERROR_AUDIO` | Mic permission/denied/AudioRecord failure | Error card + retry | `IDLE` |
| `ERROR_ENGINE` | NPU/engine failure after retries | "On-device AI engine unavailable — retrying / type report manually" | `MANUAL_FILL`, retry |
| `ERROR_PARSE` | LLM output unparseable after one repair | "Couldn't understand fully — please check the form" | `MANUAL_FILL` with transcript |

**Drafts:** a `DRAFT` row is created at the first `LISTENING`. Every turn and every form change is persisted immediately, so killing the app never loses a report. On next launch, an unfinished draft (< 24 h old) is offered for resumption.

### 8.3 Follow-up loop (detail)

```
round = 0
loop:
  result = Validator.validate(form, taxonomy)
  if result.complete: goto RECOMMENDING
  if round == 3: goto MANUAL_FILL
  q = Validator.nextQuestion(result.missing, form.lang)          // ≤2 fields, §14.4
  form.meta[q.fields].asked++ ; persist Turn(FOLLOWUP_Q, q.text)
  TTS.speak(q.text) ; await done ; wait 300 ms ; auto-start LISTENING (VAD silence 1.2 s, max 30 s)
  answer = transcribe(...)                                        // or typed text
  if answer is empty/no speech for 8 s: re-prompt once with "Sorry, I didn't catch that. " + q.text (not counted as a round)
  patch = Llama.update_incident_fields(form_compact, q, answer)   // §13.5
  merge(form, patch, provenance=turn) ; round++
```

Rules:
- A field asked twice and still missing becomes a candidate for `UNKNOWN` only if the user said so; otherwise it stays `MISSING` and goes to manual fill.
- The user can say "skip" / "chhodo" / "baad mein" → the asked fields **stay `MISSING`** (skipping ≠ not knowing; only "I don't know" produces `UNKNOWN`, D-29) and the loop moves on to the next question. This distinction matters for data quality.
- The user can always tap "Review now" to jump to `MANUAL_FILL`.
- If the answer contains corrections to other fields ("actually it was Room 3"), the patch includes them; corrections overwrite `FILLED`/`INFERRED` values but **never** `USER_EDITED` values (user edits always win).

---

## 9. Latency budget and NPU optimisation

### 9.1 Reference speeds for our SoC

| Model / phase | Figure | Source |
|---|---|---|
| Llama 3.2 3B w4a16, Genie, decode | **~28 tok/s** | `perf.yaml` (qai-hub-models 0.63) Galaxy S25 genie `[measured-AIHub]` |
| Llama 3.2 3B w4a16, Genie, prefill | **~730 tok/s** | same |
| Llama 3.2 3B time-to-first-token range | 77 ms – 2.45 s (depends on prompt length) | same |
| Whisper-small encoder (30 s window) | **~66 ms** | `perf.yaml` whisper_small S25 precompiled_qnn_onnx `[measured-AIHub]` |
| Whisper-small decoder step | **~8.4 ms/token** | same |
| Genie dialog creation (load 3 ctx bins) | ~2–4 s once at startup | `problems_setup.md` `[verified on V75]` |
| Silero VAD | ~1 ms / 32 ms window on CPU | `[inferred]` |

> The S25 table also lists a block with 13.8 tok/s / 394 tok/s prefill: that is the **w4** precision block. We use **w4a16**. `[verify H4]`: measure on our board with `--profile`.

### 9.2 Budget for a 20-second narration (P0 target ≤ 8 s, D-52)

| Stage | Budget | How |
|---|---|---|
| End-of-speech detection | 1.2 s | VAD silence window (configurable 0.8–1.5 s) |
| ASR (1 chunk, ~60 output tokens) | 0.7 s | mel 40 ms + encoder 66 ms + 60 × 8.4 ms + overhead |
| Transcript review auto-proceed | 0 s on the critical path | Extraction **starts immediately** in the background; if the user edits the transcript within the 2 s window, the running extraction is aborted and restarted with the edited text. |
| Extraction prefill | 1.2 s (cold prompt ~900 tok) → 0.2 s with prefix cache | §9.4 |
| Extraction decode | 3.5 s (~100 output tokens @ 28 tok/s) | Omit-absent-fields rule keeps output short (§13.5 rule 3) |
| Parse + normalise + validate + DB | < 50 ms | |
| **Total (cold prompt / with prefix cache)** | **~6.6 s / ~5.6 s** | First fields visible after ~2.5 s because the form streams. |

Follow-up turn (answer of ~5 s): VAD 1.2 + ASR 0.3 + patch prefill 0.6 + decode ~30 tokens 1.1 = **~3.2 s** until the next question is spoken.

Recommendations: prompt ~700 tokens, output ~120 tokens → ~5 s, but **off the critical path** (they stream into the review screen while the user reads the form).

### 9.3 Long narrations (up to 3 minutes)

Audio is split into ≤ 28 s chunks at silences **while the user is still talking**; each completed chunk is transcribed immediately (the NPU is otherwise idle during recording). When the user stops, only the last chunk (≤ 28 s) remains → ASR latency stays ≤ ~1.5 s regardless of narration length. Extraction prefill grows with transcript length (3 min ≈ 450 words ≈ 600 tokens → +0.8 s).

### 9.4 Optimisation techniques (ordered by value/effort)

| # | Technique | Gain | Priority |
|---|---|---|---|
| O1 | Keep both models resident; warm-up query at app start ("Say OK", 4 tokens) | Removes 2–4 s load + first-call jitter | P0 |
| O2 | **Omit absent fields** in tool-call output; no pretty-printing; no whitespace | Output tokens 180 → ~100 (−3 s) | P0 |
| O3 | Greedy sampling (top-k 1) for extract/patch | Deterministic, slightly faster sampler | P0 |
| O4 | Hard `max-num-tokens` per task: extract 320, patch 120, recommend 260, repair 320 | Bounds worst case | P0 |
| O5 | Start extraction during the 2 s transcript-review window | −2 s perceived | P0 |
| O6 | Pipelined chunk ASR during recording | Long narrations don't add latency | P0 |
| O7 | Stream tokens → incremental JSON parse → UI | Perceived latency ~2.5 s | P0 |
| O8 | **System-prompt KV prefix cache**: after warm-up, prefill the static system prompt once and `GenieDialog_save()` the KV state to app cache; before each call `GenieDialog_reset()` + `GenieDialog_restore()`; only the user part is prefilled | −1 s per call | P1 `[verify H8]` — if restore is slower than ~200 ms or unstable, disable via config flag `llm.prefix_cache=false` |
| O9 | Patch prompt sends a **compact form** (only filled fields, short) | Patch prefill ~400 tokens | P0 |
| O10 | Template-based emergency steps shown instantly (no LLM wait) | Critical path to safety info ~0 s | P0 |
| O11 | Recommendations aborted/re-queued when ASR needs the NPU | ASR never waits | P0 |
| O12 | Re-export Llama with `--context-lengths 2048` | ~5–10 % faster `[inferred]` | P2 (costs a full export) |
| O13 | Whisper: stop decoding at EOT; repetition guard; skip silent chunks (VAD) | Avoids 200-token hallucination loops | P0 |
| O14 | CPU work (VAD, TTS, mel) never touches the NPU | No contention | P0 |
| O15 | Thermal guard: defer recommendations and eval batches if NPU zone > 88 °C | Avoids throttling mid-demo | P1 |

### 9.5 Measurement

Every engine call returns timing stats (`ttft_ms`, `prefill_tps`, `decode_tps`, `tokens_in`, `tokens_out`, `wall_ms`). The orchestrator records per-stage timings into the `turns` table (`asr_ms`, `llm_ms`) and the debug overlay shows the last turn's breakdown. The eval harness reports p50/p90 (§29.4).

---
## 10. Incident taxonomy

The taxonomy is the contract between the LLM, the validator, the question bank, the SOP library, the database and the evaluation. It lives in **`config/taxonomy.json`** (complete file in Appendix A). The Android build copies `config/` into the APK assets (§20.10); the Python data generator and evaluator read the same files. **Nobody hard-codes a field name, enum value or category anywhere else.**

### 10.1 Design principles

1. Categories are **general** (RA.txt): they only select which category fields and questions apply. Fine-grained meaning lives in the fields (`electrical_hazard = SPARKING`), not in more categories.
2. Every field has: `key`, `type`, `label`, `mandatory`, `group`, `priority`, optional `enum`, and per-language question templates.
3. Category field keys are **globally unique** across categories, so a flat `details` object never collides when primary and secondary categories are merged (D-20).
4. Enum values are UPPER_SNAKE English. The LLM may output loose synonyms; the parser maps them through `synonyms` (§13.6).
5. Every enum that describes a yes/no fact includes `UNKNOWN`, so "I don't know" has a home.

### 10.2 Common fields (every incident)

| key | type | Mandatory | group | priority | Description / extraction rule |
|---|---|---|---|---|---|
| `incident_type` | enum (category code) | **Yes** | `type` | 1 | Primary category (§10.3). |
| `secondary_types` | enum[] (≤2) | No | — | — | Other categories clearly present. |
| `title` | string ≤ 80 chars | No (system) | — | — | Short English headline, generated by the LLM. If absent, built as `<Category label> at <location>`. Never asked. |
| `description` | string | **Yes** | `type` | 2 | 1–3 sentence English summary of what happened. Practically always extractable from the narration. |
| `severity` | enum `LOW/MEDIUM/HIGH/CRITICAL` | **Yes** | `impact` | 5 | Proposed by LLM from the rubric (§10.6); may be raised by rules; user can override. |
| `location` | string | **Yes** | `where_when` | 3 | As spoken, translated to English; proper names kept ("Conference Room 2"). Matched to registry (§15.2). |
| `occurred_at` | datetime (resolved) | **Yes** | `where_when` | 4 | LLM outputs `time` = the raw time phrase in English; resolver produces `occurred_at` + `occurred_at_approx` (§15.1). |
| `injuries` | enum `YES/NO/UNKNOWN` | **Yes** | `impact` | 6 | Whether anyone was hurt. "Persons involved/injured" (D-26). |
| `injured_count` | int ≥0 | No | `impact` | — | If stated. |
| `persons` | array of `{name, role, injured, injury}` | No | — | — | `role ∈ AFFECTED, INVOLVED, RESPONDER, REPORTER, OTHER`; `injured` bool. Names only if spoken. |
| `equipment` | string[] | No (Yes for `EQUIPMENT`) | `hazard` | — | Equipment / asset names or tags ("Pump P-101", "AC unit"). |
| `actions_taken` | string[] | No | — | — | Immediate actions already taken ("Area isolated by maintenance"). |
| `witnesses` | array of `{name, contact}` | No | — | — | People who saw it. |
| `near_miss` | bool | No | — | — | True if the speaker says nothing/no one was harmed and it "almost" happened (D-21). |
| `hazard_ongoing` | enum `YES/NO/UNKNOWN` | No | `impact` | — | Is the danger still present now? Feeds severity rules. |
| `other_incidents` | string[] | No | — | — | Summaries of separate incidents in the same narration (D-56). |
| `lang` | enum `en/hinglish/hi` | No (system) | — | — | Language the speaker used; drives follow-up language (D-38). |

System-filled fields (never from the LLM): `reporter_name`, `reporter_emp_id` (profile, D-25), `reported_at` (device clock), `location_id` (registry), `occurred_at`, `occurred_at_approx`, `ref_no`, `status`.

### 10.3 Categories

| Code | Label | Covers | Typical cues (EN / Hinglish) | Not this when… |
|---|---|---|---|---|
| `FIRE` | Fire / Smoke / Explosion | Flames, smoke, burning smell, explosion, blast, short-circuit fire | fire, flames, smoke, burning, blast · aag, dhuan, jal raha, dhamaka | Only sparks, no fire → `ELECTRICAL`. Fire drill → `OTHER`. |
| `MEDICAL` | Medical Emergency / Injury | Any injury or illness to a person: falls with injury, cuts, burns, fainting, heart attack, heat stroke | injured, hurt, fell, bleeding, fainted · chot lagi, gir gaya, khoon, behosh, chakkar | A fall *without* injury → the hazard's category (`STRUCTURAL`) + `near_miss`. |
| `WATER` | Water Leak / Flooding | Pipe burst, leaking roof/AC, overflowing tank, waterlogging indoors | leak, flooding, water on floor · paani leak, paani bhar gaya, tapak raha | Chemical liquid → `HAZMAT`; sewage into drain outdoors → `ENVIRONMENTAL`. |
| `ELECTRICAL` | Electrical Hazard / Outage | Shock, sparking, exposed wiring, overheating panels, power failure | shock, sparks, short circuit, wire, power cut · current laga, spark, bijli gayi, taar | Visible fire → `FIRE` (+ `ELECTRICAL` secondary). |
| `HAZMAT` | Gas / Chemical / Hazardous Substance | Gas leaks, chemical spills or fumes **with exposure risk to people** | gas smell, chemical, fumes, acid, ammonia · gas ki smell, chemical gir gaya | Release that mainly harms the environment (drain/soil/air outside) → `ENVIRONMENTAL`. |
| `EQUIPMENT` | Equipment / Machinery Failure | Breakdown or malfunction of machines, tools, HVAC, pumps, IT hardware in production | machine stopped, breakdown, jammed, not working · machine band, kharab, atak gaya | If the failure caused fire/injury, those are primary; `EQUIPMENT` secondary. |
| `SECURITY` | Security / Theft / Intrusion / Violence | Theft, break-in, trespass, unauthorised access, vandalism, assault, threats, suspicious activity | stolen, broke in, unknown person, fight · chori, ghus gaya, ladai, dhamki | — |
| `STRUCTURAL` | Structural / Facility Damage | Ceiling/wall damage, cracks, broken glass, collapsed racks, slip/trip hazards, stuck lift, damaged stairs/doors | ceiling fell, crack, broken glass, wet floor, lift stuck · chhat gir gayi, darar, sheesha toota, lift atak gayi | Water source of wet floor → `WATER` primary. |
| `ENVIRONMENTAL` | Environmental Release / Pollution | Oil/chemical release to drains, soil or water bodies; abnormal emissions; improper waste disposal | oil in drain, smoke from chimney, waste dumped · naale mein tel, kachra phenka | Exposure risk to people → `HAZMAT`. |
| `VEHICLE` | Vehicle / Traffic / Forklift | Collisions involving forklifts, trucks, cars, bikes on premises; vehicle–person contact | forklift hit, truck reversed, accident · gaadi thok di, forklift se takkar | — |
| `OTHER` | Other | Anything not covered above | — | Use only when no category fits. |

**Classification tie-break rules** (included in the extraction prompt, §Appendix C.1):
1. If a person was injured, `MEDICAL` is primary **unless** the cause is an ongoing fire, gas/chemical release or electrical hazard (then that hazard is primary and `MEDICAL` is secondary).
2. Fire beats electrical; hazmat beats environmental when people are exposed.
3. Secondary types only when the narration contains clear evidence (e.g. "water leaked onto the panel and it started sparking" → `WATER` + `ELECTRICAL`).

### 10.4 Category-specific fields

M = mandatory for that category. Groups drive question pairing (§14.4). Enum values listed exactly.

**FIRE**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `fire_status` | enum | **M** | `hazard` | `ACTIVE, CONTAINED, EXTINGUISHED, SMOKE_ONLY, UNKNOWN` |
| `evacuation_status` | enum | **M** | `response` | `COMPLETED, IN_PROGRESS, NOT_STARTED, NOT_REQUIRED, UNKNOWN` |
| `fire_brigade_called` | enum | **M** | `response` | `YES, NO, UNKNOWN` |
| `ignition_source` | string | | `hazard` | e.g. "electrical panel", "cigarette" |
| `extinguisher_used` | enum | | `response` | `YES, NO, UNKNOWN` |
| `explosion_occurred` | bool | | `hazard` | |

**MEDICAL**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `patient_condition` | enum | **M** | `victim` | `CONSCIOUS_STABLE, CONSCIOUS_DISTRESSED, UNCONSCIOUS, NOT_BREATHING, DECEASED, UNKNOWN` |
| `first_aid_given` | enum | **M** | `response` | `YES, NO, UNKNOWN` |
| `ambulance_called` | enum | **M** | `response` | `YES, NO, NOT_NEEDED, UNKNOWN` |
| `injury_nature` | string | | `victim` | "fracture", "cut", "burn", "sprain" |
| `body_part` | string | | `victim` | |
| `injury_cause` | string | | `hazard` | "slipped on wet floor" |

**WATER**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `leak_source` | string | **M** | `hazard` | "burst pipe", "AC drain", "roof" |
| `leak_status` | enum | **M** | `hazard` | `ONGOING, CONTROLLED, STOPPED, UNKNOWN` |
| `water_supply_isolated` | enum | | `response` | `YES, NO, UNKNOWN` |
| `affected_extent` | string | | `hazard` | "whole corridor", "2 desks" |
| `electrical_risk` | enum | | `hazard` | `YES, NO, UNKNOWN` (water near electrics) |

**ELECTRICAL**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `electrical_hazard` | enum | **M** | `hazard` | `SHOCK, SPARKING, SHORT_CIRCUIT, EXPOSED_WIRING, OVERHEATING, POWER_OUTAGE, OTHER` |
| `power_isolated` | enum | **M** | `response` | `YES, NO, UNKNOWN` |
| `outage_scope` | string | | `hazard` | "entire Block B" |
| `affected_circuit` | string | | `hazard` | "DB-3", "panel near lift" |

**HAZMAT**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `substance` | string | **M** | `hazard` | "LPG", "ammonia", "unknown chemical" |
| `release_status` | enum | **M** | `hazard` | `ONGOING, CONTAINED, STOPPED, UNKNOWN` |
| `area_evacuated` | enum | **M** | `response` | `YES, NO, PARTIAL, UNKNOWN` |
| `quantity_estimate` | string | | `hazard` | "about 5 litres" |
| `exposure_symptoms` | string | | `victim` | "coughing, eye irritation" |
| `ppe_used` | enum | | `response` | `YES, NO, UNKNOWN` |

**EQUIPMENT** (common `equipment` becomes mandatory)
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `equipment` (common) | string[] | **M** | `hazard` | |
| `failure_description` | string | **M** | `hazard` | "conveyor belt jammed and motor smoking" |
| `operational_status` | enum | **M** | `response` | `STOPPED, DEGRADED, RUNNING, UNKNOWN` |
| `lockout_tagout` | enum | | `response` | `YES, NO, UNKNOWN` |
| `production_impact` | string | | `hazard` | "Line 2 halted" |

**SECURITY**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `security_event` | enum | **M** | `hazard` | `THEFT, INTRUSION, UNAUTHORIZED_ACCESS, VANDALISM, ASSAULT, THREAT, SUSPICIOUS_ACTIVITY, OTHER` |
| `security_informed` | enum | **M** | `response` | `YES, NO, UNKNOWN` |
| `suspect_description` | string | | `hazard` | |
| `items_affected` | string[] | | `hazard` | |
| `police_informed` | enum | | `response` | `YES, NO, UNKNOWN` |

**STRUCTURAL**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `damage_type` | string | **M** | `hazard` | "ceiling panel fell", "wet floor", "lift stuck between floors" |
| `area_cordoned` | enum | **M** | `response` | `YES, NO, UNKNOWN` |
| `people_trapped` | enum | | `victim` | `YES, NO, UNKNOWN` |

**ENVIRONMENTAL**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `pollutant` | string | **M** | `hazard` | "diesel", "untreated effluent" |
| `release_medium` | enum | **M** | `hazard` | `AIR, DRAIN, WATER_BODY, SOIL, MULTIPLE, UNKNOWN` |
| `containment_status` | enum | **M** | `response` | `ONGOING, CONTAINED, STOPPED, UNKNOWN` |
| `env_quantity` | string | | `hazard` | |

**VEHICLE**
| key | type | M | group | enum / notes |
|---|---|---|---|---|
| `vehicles_involved` | string[] | **M** | `hazard` | "forklift", "delivery truck" |
| `collision_type` | enum | **M** | `hazard` | `VEHICLE_VEHICLE, VEHICLE_PERSON, VEHICLE_OBJECT, ROLLOVER, OTHER, UNKNOWN` |
| `vehicle_ids` | string[] | | `hazard` | number plates / fleet IDs |
| `driver_name` | string | | `victim` | |
| `traffic_blocked` | enum | | `response` | `YES, NO, UNKNOWN` |

**OTHER** — no category fields; only common mandatory fields apply.

### 10.5 Mandatory set resolution

```
mandatory(form) = COMMON_MANDATORY                       // incident_type, description, location, occurred_at, severity, injuries
                ∪ CAT_MANDATORY[form.incident_type]
                ∪ ⋃ CAT_MANDATORY[t] for t in form.secondary_types
                ∪ {equipment} if EQUIPMENT ∈ types
```

Worst realistic case (FIRE + MEDICAL + ELECTRICAL): 6 + 3 + 3 + 2 = 14 mandatory fields. With pairing (2 per question) and typical narrations already covering half of them, 3 rounds suffice in most cases; the rest falls to manual fill (D-28).

### 10.6 Severity rubric (in the prompt) and deterministic floor (in code)

**Rubric (LLM):**
| Level | Definition |
|---|---|
| `CRITICAL` | Life-threatening or uncontrolled: unconscious/not breathing person, fire spreading, ongoing gas/chemical release with people exposed, people trapped, structural collapse with people at risk, electrocution. |
| `HIGH` | Serious injury needing medical treatment beyond first aid; hazard active but being controlled; major damage; production stopped; assault or ongoing intrusion. |
| `MEDIUM` | Minor injury (first aid only); hazard controlled; limited damage; equipment degraded; theft without harm. |
| `LOW` | No injury and minimal damage; near miss; hazard already resolved; cosmetic issues. |

**Deterministic floor (Kotlin `SeverityRules`)** — raises, never lowers, and sets `severity_source = RULE`:
| Condition | Minimum severity |
|---|---|
| `patient_condition ∈ {UNCONSCIOUS, NOT_BREATHING, DECEASED}` | CRITICAL |
| `people_trapped = YES` | CRITICAL |
| `fire_status = ACTIVE` | HIGH (CRITICAL if `evacuation_status ∈ {NOT_STARTED, IN_PROGRESS}`) |
| `HAZMAT` and `release_status = ONGOING` | HIGH |
| `electrical_hazard = SHOCK` and `injuries = YES` | CRITICAL |
| `injuries = YES` | MEDIUM |
| `collision_type = VEHICLE_PERSON` | HIGH |

If the LLM omitted severity and the rules produce a level, the field becomes `INFERRED` (counts as filled). If neither gives a value, severity is asked (§11).

### 10.7 Emergency detection (D-57)

Two triggers, both instant:

1. **Keyword trigger (before the LLM, on the raw transcript, < 1 ms):** case-insensitive regex list in `taxonomy.json → emergency.keywords` (Appendix A), covering English, Roman Hinglish and Devanagari: e.g. `fire spreading|aag (lag|fail)|आग|explosion|blast|dhamaka|unconscious|behosh|बेहोश|not breathing|saans nahi|gas leak|gas ki (smell|badbu)|electrocut|current laga|trapped|phas(a|e) (gaya|hue)|heavy bleeding|bahut khoon`. Excludes false-positive phrases (`fire drill|fire extinguisher (check|inspection)|mock drill`).
   → **Amber banner** "Possible emergency" with the category's `immediate` SOP steps (§12) and a **Call ERT** button.
2. **Severity trigger (after extraction or rules):** `severity = CRITICAL` → **red banner** "Emergency" (replaces amber), steps for the extracted category, Call ERT button, and the follow-up loop is shortened to **only common mandatory fields** (category details can be filled later in review).

Call ERT = `Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + ertNumber))` — no `CALL_PHONE` permission needed. Default `ertNumber = "112"`, configurable in Settings (site ERT extension). The QIDK has no SIM; on the demo the dialer opens, which is the expected behaviour. The banner records `emergency_flag = 1` and the time it was shown.

---

## 11. Follow-up question bank

Questions are **templates**, not LLM-generated (D-27): instant, consistent, translatable, testable. They live in `taxonomy.json` under each field (`q.en`, `q.hinglish`) plus **pair templates** for common pairs. Hinglish templates are in Roman script and are read by the eSpeak `en-IN` voice (§17.3), which pronounces Roman Hinglish acceptably; Devanagari is never sent to TTS.

### 11.1 Common fields

| field | English | Hinglish (Roman) |
|---|---|---|
| `incident_type` | What kind of incident is this — for example a fire, a leak, an injury, or an equipment problem? | Yeh kis type ka incident hai — jaise aag, leak, chot, ya machine ki problem? |
| `description` | Could you briefly describe what happened? | Thoda batayiye, exactly kya hua? |
| `location` | Where exactly did this happen? | Yeh exactly kahan hua? |
| `occurred_at` | Around what time did it happen? | Yeh lagbhag kitne baje hua? |
| `severity` | How serious is it right now — minor, significant, or is anyone in danger? | Abhi situation kitni serious hai — chhoti problem, badi problem, ya kisi ki jaan ko khatra hai? |
| `injuries` | Was anyone hurt? | Kya kisi ko chot lagi? |
| `equipment` | Which machine or equipment is involved? | Kaunsi machine ya equipment involved hai? |

**Pair templates** (used when both fields of a pair are missing):
| pair | English | Hinglish |
|---|---|---|
| `location + occurred_at` | Where exactly did this happen, and around what time? | Yeh kahan hua, aur lagbhag kitne baje? |
| `injuries + severity` | Was anyone hurt, and how serious is the situation right now? | Kya kisi ko chot lagi, aur abhi situation kitni serious hai? |
| `incident_type + description` | Could you tell me what happened? | Batayiye, kya hua? |

### 11.2 Category fields

| field | English | Hinglish |
|---|---|---|
| `fire_status` | Is the fire still burning, or is it under control? | Aag abhi bhi lagi hai, ya control mein hai? |
| `evacuation_status` | Has the area been evacuated? | Kya area khaali karwa diya gaya hai? |
| `fire_brigade_called` | Has the fire brigade been called? | Kya fire brigade ko call kiya gaya? |
| `patient_condition` | Is the injured person conscious and breathing? | Kya ghayal vyakti hosh mein hai aur saans le raha hai? |
| `first_aid_given` | Has first aid been given? | Kya first aid diya gaya? |
| `ambulance_called` | Has an ambulance been called? | Kya ambulance bulayi gayi? |
| `leak_source` | Where is the water coming from? | Paani kahan se aa raha hai? |
| `leak_status` | Is the water still leaking, or has it stopped? | Kya paani abhi bhi leak ho raha hai, ya ruk gaya? |
| `electrical_hazard` | What exactly is the electrical problem — sparking, a shock, exposed wires, or a power cut? | Electrical problem kya hai — spark, current laga, khula taar, ya light chali gayi? |
| `power_isolated` | Has the power supply been switched off? | Kya power supply band kar di gayi hai? |
| `substance` | What gas or chemical is it? | Kaunsi gas ya chemical hai? |
| `release_status` | Is it still leaking, or has it been contained? | Kya abhi bhi leak ho raha hai, ya control ho gaya? |
| `area_evacuated` | Have people been moved away from the area? | Kya logon ko us area se hata diya gaya? |
| `failure_description` | What exactly went wrong with the equipment? | Machine mein exactly kya kharabi hui? |
| `operational_status` | Is the equipment stopped, or still running? | Machine band hai, ya abhi bhi chal rahi hai? |
| `security_event` | What happened exactly — theft, a break-in, unauthorised entry, or a fight? | Exactly kya hua — chori, ghuspaith, bina permission entry, ya ladai? |
| `security_informed` | Has the security team been informed? | Kya security team ko bataya gaya? |
| `damage_type` | What kind of damage is it? | Kis tarah ka damage hua hai? |
| `area_cordoned` | Has the area been cordoned off? | Kya area ko ghera/band kar diya gaya hai? |
| `pollutant` | What was released? | Kya cheez release hui ya phaili? |
| `release_medium` | Did it go into a drain, the soil, water, or the air? | Kya yeh naale, mitti, paani ya hawa mein gaya? |
| `containment_status` | Is it still spreading, or has it been contained? | Kya yeh abhi bhi phail raha hai, ya rok diya gaya? |
| `vehicles_involved` | Which vehicles were involved? | Kaunsi gaadiyan involved thi? |
| `collision_type` | Did the vehicle hit another vehicle, a person, or an object? | Gaadi ne dusri gaadi, kisi insaan, ya kisi cheez ko takkar maari? |

**Group pair rule:** when no explicit pair template exists, two fields of the same `group` are combined as `"<q1 without final ?>, and <q2 with first letter lower-cased>"` (EN) or `"<q1 without ?>, aur <q2>"` (Hinglish). Example: "Is the fire still burning, or is it under control, and has the area been evacuated?" — acceptable; P3 SHOULD add explicit pair templates for the frequent pairs (`fire_status+evacuation_status`, `first_aid_given+ambulance_called`, `leak_source+leak_status`).

### 11.3 Re-prompt phrases

| Situation | English | Hinglish |
|---|---|---|
| No speech in 8 s | Sorry, I didn't catch that. {question} | Maaf kijiye, sunaai nahi diya. {question} |
| Answer didn't fill anything | I still need: {field labels}. {question} | Mujhe abhi bhi chahiye: {field labels}. {question} |
| Round limit reached | Thanks. Please check the highlighted fields on the screen. | Dhanyavaad. Screen par highlighted fields check kar lijiye. |
| Validation complete | Thank you. I have everything I need. Here are the recommended next steps. | Dhanyavaad. Saari jaankari mil gayi. Yeh rahe agle steps. |

---

## 12. SOP / recommendation library

### 12.1 Structure (`config/sop.json`, complete in Appendix B)

```jsonc
{
  "version": 1,
  "categories": {
    "WATER": [
      {"id": "WATER-01", "text": "Isolate the water supply valve feeding the leak.", "immediate": true,  "when": {"leak_status": ["ONGOING","UNKNOWN"]}},
      {"id": "WATER-02", "text": "Switch off electrical equipment and sockets in the wet area if it is safe to do so.", "immediate": true, "when": {"electrical_risk": ["YES","UNKNOWN"]}},
      ...
    ]
  }
}
```

- `immediate: true` steps form the **emergency banner** list (§10.7) and are always candidates.
- `when` is an optional pre-filter evaluated in Kotlin against the current form (missing field ⇒ condition treated as satisfied). It shrinks the candidate list sent to the LLM (fewer prompt tokens, less room for error).
- Each category has 8–10 steps. A `GENERAL` list (documentation, notify supervisor, preserve evidence, update report) is appended to every incident.
- Steps are written from generic EHS practice (OSHA, ISO 45001, NFPA/NBC fire guidance, NDMA guidance) and are **advisory**; the review screen shows "Recommendations follow site SOPs; follow your site's emergency procedures first."

### 12.2 Selection and tailoring

1. Kotlin builds the candidate list: `immediate` steps + `when`-satisfied steps of the primary and secondary categories + `GENERAL`, then removes steps whose effect is already in `actions_taken` (simple keyword overlap ≥ 0.6 with the step's `done_keywords`).
2. Llama receives the compact form + candidate list and calls `propose_actions` (≤ 6 actions, urgency order). Each action references an SOP id and adapts the text with specifics ("Isolate the water supply valve feeding the leak near Conference Room 2"). At most **one** `CUSTOM` action is allowed.
3. Post-check in Kotlin: drop actions whose `sop` id is not in the candidate list (except one `CUSTOM`), truncate text to 160 chars, ensure every `immediate` step relevant to a `CRITICAL`/`HIGH` incident is present (re-insert from template if the LLM dropped it).
4. **Fallback** (LLM error/timeout 12 s): use the top 5 candidates verbatim. The UI never shows an empty recommendation list.
5. Sampler for this call: temp 0.3, top-k 40, top-p 0.95, seed 42 (D-33) — some variety in wording, but the ids are constrained.

---
## 13. LLM contract: tool calling, prompts, parsing, grounding

### 13.1 Roles of the single LLM

One Genie dialog, one loaded bundle (Llama 3.2 3B Instruct, w4a16), four **tasks**, each with its own prompt template, sampler and token cap:

| Task | Tool emitted | Prompt template | Sampler | max tokens | When |
|---|---|---|---|---|---|
| `extract` | `fill_incident_form` | `config/prompts/extract.system.txt` + few-shots + `extract.user.txt` | greedy (top-k 1) | 320 | After narration |
| `patch` | `update_incident_fields` | `patch.system.txt` + `patch.user.txt` | greedy | 120 | After each follow-up answer / per-field voice edit |
| `recommend` | `propose_actions` | `recommend.system.txt` + `recommend.user.txt` | temp 0.3, top-k 40, top-p 0.95, seed 42 | 260 | After validation complete |
| `repair` | (same tool as the failed task) | `repair.system.txt` + `repair.user.txt` | greedy | 320 | Only after a parse failure (§13.7) |

### 13.2 Why JSON tool calls (and how they map to the slide's "function calling")

Llama 3.2 Instruct is trained for **JSON-based custom tool calling**: tools are described in the system prompt and the model answers with `{"name": <tool>, "parameters": {...}}`. The app keeps a **function registry** and executes the call:

| Function (app side) | Called by | Effect |
|---|---|---|
| `fill_incident_form(params)` | LLM (`extract`) | Normalise → merge into the draft form → persist → validator |
| `update_incident_fields(params)` | LLM (`patch`) | Normalise → merge with provenance → persist → validator |
| `propose_actions(params)` | LLM (`recommend`) | Post-check against SOP candidates → persist recommendations |
| `ask_followup(fields)` | Validator | Render template → TTS → listen |
| `submit_incident_report(id)` | User (Submit button) | Final validation → status SUBMITTED → FTS index → confirmation |
| `trigger_emergency_banner(category, level)` | Keyword scan / severity rules | Banner + steps + dial button |

The debug overlay shows the raw tool call JSON and the executed function — this is how we *show* function calling to the judges.

### 13.3 Chat format (exact bytes)

We render prompts ourselves (no HF `apply_chat_template` at runtime) so that **training data, host evaluation and device inference use byte-identical prompts**. Renderer = `PromptBuilder` (Kotlin) and `ml/common/prompt_builder.py` (Python); both read the same template files and MUST produce identical strings (golden test `PromptBuilderGoldenTest` compares against `config/prompts/golden/*.txt`).

```
<|start_header_id|>system<|end_header_id|>\n\n{SYSTEM}<|eot_id|>
<|start_header_id|>user<|end_header_id|>\n\n{FEWSHOT_USER_1}<|eot_id|>
<|start_header_id|>assistant<|end_header_id|>\n\n{FEWSHOT_ASSISTANT_1}<|eot_id|>
... (more few-shot pairs)
<|start_header_id|>user<|end_header_id|>\n\n{USER}<|eot_id|>
<|start_header_id|>assistant<|end_header_id|>\n\n
```
(`\n` = real newline; the line breaks shown between `<|eot_id|>` and the next header are for readability only — there is **no** newline there.)

- **No `<|begin_of_text|>`** — Genie prepends BOS itself (§13.9).
- Stop tokens: `<|eot_id|>` (128009), `<|eom_id|>` (128008), `<|end_of_text|>` (128001) — configured in `genie_config.json` `eos-token`.
- We omit Llama's default "Cutting Knowledge Date / Today Date" system preamble. Training data is rendered the same way.

### 13.4 Prompt templates (summary; full text in Appendix C)

**Extraction system prompt** (`extract.system.txt`, ~750 tokens) contains, in order:
1. Role: "You are IncidentVoice, an incident-report extraction engine for an industrial plant and office campus."
2. Input description: transcript in English, Hinglish or Hindi, may contain speech-recognition errors.
3. The 9 extraction rules (§13.5).
4. Category list with one-line definitions and the tie-break rules (§10.3).
5. Severity rubric (§10.6).
6. Category field catalogue: one line per category: `FIRE: fire_status(ACTIVE|CONTAINED|EXTINGUISHED|SMOKE_ONLY|UNKNOWN), evacuation_status(...), ...` — generated from `taxonomy.json` by `PromptBuilder.renderFieldCatalogue()` so it can never drift from the taxonomy.
7. Tool definition (JSON schema, compact, Appendix D.1).
8. Output instruction: "Respond with ONLY the JSON function call, no other text."

Two few-shot pairs follow (one English = the slide's water-leak example; one Hinglish with injury + relative time). Few-shots live in `config/prompts/extract.fewshot.jsonl` and are also used verbatim as training examples.

**User message** (`extract.user.txt`):
```
Transcript:
<<<
{{transcript}}
>>>
```

### 13.5 Extraction rules (verbatim in the prompt)

1. Output exactly one JSON object `{"name": "fill_incident_form", "parameters": {...}}` and nothing else.
2. Write every value in clear, simple English, even if the speaker used Hindi or Hinglish. Keep names of people, rooms, buildings, machines and asset tags exactly as spoken (transliterated to Roman letters if needed).
3. Include a field **only** if the transcript states it or it is directly implied. Never guess. **Omit** fields you do not know — do not output null, empty strings or "unknown".
4. Use `UNKNOWN` for an enum field only when the speaker explicitly says they do not know.
5. `time`: the time phrase as spoken, translated to English ("around 10 AM", "about an hour ago", "last night at 11"). Do not compute dates.
6. `incident_type`: the one best category code; `secondary_types`: up to 2 more, only with clear evidence; follow the tie-break rules.
7. `severity`: choose using the rubric only if the transcript gives enough information.
8. `details`: only fields that belong to the chosen categories, with the listed enum values.
9. If the speaker describes another, separate incident, summarise it in `other_incidents` and do not mix its details in. `lang`: "en", "hinglish" or "hi" — the language the speaker used.

**Worked example (the slide's input):**

Transcript: `There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.`

Output:
```json
{"name":"fill_incident_form","parameters":{"lang":"en","incident_type":"WATER","title":"Water leak near Conference Room 2","description":"A water leak occurred near Conference Room 2. Maintenance has isolated the area.","location":"near Conference Room 2","time":"around 10 AM","actions_taken":["Maintenance isolated the area"]}}
```
→ Validator: missing `severity`, `injuries` (common, group `impact`) and `leak_source`, `leak_status` (WATER, group `hazard`).
→ Round 1 (pair template): "Was anyone hurt, and how serious is the situation right now?" — "No one is hurt, it's minor." → patch `{"injuries":"NO","severity":"LOW"}`.
→ Round 2 (group pair): "Where is the water coming from, and is the water still leaking, or has it stopped?" — "A pipe under the sink burst, it's stopped now." → patch `{"leak_source":"burst pipe under the sink","leak_status":"STOPPED"}`.
→ Complete → recommendations → review. This exact dialogue is demo scene 1 (§38).

### 13.6 Parsing and normalisation (`ToolCallParser`)

Pipeline applied to every LLM output:
1. **Extract JSON:** strip code fences; take the substring from the first `{` to its matching `}` (brace counter aware of strings). If the output was cut at `max tokens`, auto-close open strings/arrays/objects.
2. **Lenient fixes:** trailing commas, single quotes → double quotes, Python literals (`True/False/None`), unquoted enum values.
3. **Parse** with kotlinx.serialization `Json { isLenient = true; ignoreUnknownKeys = true }` into `JsonObject`.
4. **Envelope check:** `name` must equal the tool expected for the task (a model may emit `"function"` or nest `"arguments"` — accept `parameters|arguments|args` as aliases).
5. **Field routing:** keys are looked up in `taxonomy.json`; unknown keys are logged and dropped. For `update_incident_fields`, keys in `set` are routed to common or category fields by the same lookup.
6. **Value normalisation:**
   - enums: upper-case, spaces→`_`, then `synonyms` map from taxonomy (e.g. `"PUT OUT" → EXTINGUISHED`, `"UNDER CONTROL" → CONTAINED`, `"HAAN" → YES`, `"NAHI" → NO`); invalid → dropped + logged.
   - booleans: `yes/true/haan/1` → true; `no/false/nahi/0` → false.
   - ints: digits or number words (EN + Hinglish `ek, do, teen, char, paanch…`).
   - strings: trim, collapse whitespace, max 300 chars; drop `""`, `"n/a"`, `"none"`, `"not mentioned"`, `"unknown"` (for strings).
   - arrays: accept a single string as a one-element array; dedupe case-insensitively.
7. **Schema validation** against Appendix D (fails are dropped field-by-field, never the whole call).
8. **Grounding check** (§13.8).
9. Return `ParsedCall(tool, fields: Map<String, FieldValue>, dropped: List<Issue>)`.

**Incremental (streaming) parse:** while tokens stream, the same steps 1–6 run on the growing buffer every ~5 tokens with auto-closing; fields that parse successfully are shown in the UI with a "pending" shimmer, and committed only when the final parse completes.

### 13.7 Failure handling for LLM output

| Failure | Action |
|---|---|
| Output not parseable after lenient fixes | One `repair` call: system "Fix this into valid JSON for tool X; output only JSON", user = broken text. Greedy, max 320. |
| Still unparseable | `ERROR_PARSE` → `MANUAL_FILL`; `description` = transcript (system-filled, status `DEFAULTED`), everything else empty. Logged as `IV-E401`. |
| Wrong tool name | Treated as parse failure of that task. |
| Empty output (0 tokens) | **Never treated as a content failure.** It is an engine error (`IV-E6xx`) → retry policy §31.3. (This was a real bug on the previous project: an NPU failure was shown as a model rejection.) |
| Output contains fields from categories not chosen | Kept only if their category is in `incident_type ∪ secondary_types`; else dropped. |

### 13.8 Grounding (anti-hallucination)

For free-text fields where hallucination is harmful, `Grounding.check(value, transcriptsSoFar)`:
- Tokenise value and transcript (lower-case, strip punctuation, drop stopwords EN+Hinglish list, apply a 120-entry Hinglish→English lexicon to the transcript, e.g. `seedhi→stairs`, `paani→water`, `naala→drain`, `taar→wire`).
- Grounded if any content token (≥3 chars) of the value appears in the transcript, or token-set fuzzy ratio ≥ 0.8.

| Field kind | Not grounded → |
|---|---|
| `persons[].name`, `witnesses[].name`, `witnesses[].contact`, `driver_name`, `vehicle_ids` | **Dropped** (we never invent people or IDs). |
| `location`, `equipment`, `substance`, `leak_source`, `pollutant`, `damage_type`, other strings | Kept with status `INFERRED` (amber "check" chip in review). |
| `description`, `title` | Exempt (paraphrases by design). |
| Enums | Exempt (judged by rubric), but rule-checked (§10.6). |

### 13.9 Device prompt hygiene checklist (do at H4, again after any bundle change)

1. **BOS parity:** run the same prompt through the host tokenizer (`tokenizer.json`) and Genie with `--profile`; Genie's prompt token count MUST equal host count + 1 (Genie's own BOS) — or exactly host count if our prompt erroneously starts with BOS. Expected: host count + 1. If off by one more, a double BOS is present → strip it.
2. **Sampler:** confirm `genie_config.json` is not at AI Hub's default `temp 0.8 / top-k 40` for extraction. Our runtime overrides the sampler per task (§18.4); verify by running the same extract prompt twice → identical bytes.
3. **Stop tokens:** output ends at `<|eot_id|>`; no trailing text.
4. **Context:** prompt + max tokens < 4096 (our worst case: 750 system + 400 few-shot + 700 transcript + 320 output ≈ 2.2k).

---

## 14. Validator specification

### 14.1 Inputs and outputs

```kotlin
data class FieldState(
    val key: String,
    val value: JsonElement?,          // null when MISSING or UNKNOWN
    val status: FieldStatus,
    val source: FieldSource,          // LLM_EXTRACT, LLM_PATCH, RULE, USER, PROFILE, SYSTEM
    val turnId: Long?,                // provenance
    val grounded: Boolean = true,
    val askedCount: Int = 0
)
enum class FieldStatus { MISSING, FILLED, INFERRED, UNKNOWN, USER_EDITED, DEFAULTED }

data class ValidationResult(
    val complete: Boolean,
    val required: List<String>,       // resolved mandatory keys (§10.5)
    val missing: List<String>,        // required and not satisfied
    val invalid: List<FieldIssue>,    // present but failing validity checks
    val progress: Float               // satisfied / required
)

object Validator {
    fun validate(form: IncidentForm, tx: Taxonomy, now: Instant): ValidationResult
    fun nextQuestion(result: ValidationResult, form: IncidentForm, tx: Taxonomy, emergencyMode: Boolean): FollowUpQuestion?
}
data class FollowUpQuestion(val fields: List<String>, val textEn: String, val textHinglish: String)
```

Pure Kotlin, no Android imports (lives in `:core`, unit-tested on the JVM).

### 14.2 Satisfied / valid

A required field is **satisfied** iff `status ∈ {FILLED, INFERRED, UNKNOWN, USER_EDITED, DEFAULTED}` **and** it passes validity:

| Type | Valid when |
|---|---|
| enum | value ∈ allowed enum |
| string | ≥ 2 visible characters |
| string[] / object[] | ≥ 1 element, each element valid |
| `occurred_at` | resolved instant ≤ now + 5 min and ≥ now − 30 days (else `invalid` with reason `FUTURE_TIME` / `TOO_OLD`); `UNKNOWN` allowed |
| `incident_type` | any category code incl. `OTHER` |

`UNKNOWN` satisfies **every** mandatory field (D-29) except `incident_type` and `description` (always derivable; if the user truly cannot say, `incident_type=OTHER` and `description` = transcript).

### 14.3 Ordering of missing fields

Sort key: `(isCommon ? 0 : 1, group order, priority, key)` where group order = `type, where_when, impact, victim, hazard, response`. Rationale: people safety (`victim`) before property (`hazard`) before response actions.

Fields with `askedCount ≥ 2` are skipped by `nextQuestion` (they go to manual fill).

### 14.4 Pairing (D-27)

```
first   = missingSorted.first { askedCount < 2 }
partner = missingSorted.firstOrNull { it != first && group(it) == group(first) && askedCount(it) < 2 }
text    = pairTemplate(first, partner) ?: groupRule(first, partner) ?: single(first)
```
- In **emergency mode** (`severity = CRITICAL`, D-57) only common mandatory fields are considered.
- Language: `form.lang == "en"` → English template; `hinglish` or `hi` → Hinglish template.

### 14.5 Submit gate

`submit_incident_report` re-runs `validate()`; if `!complete`, Submit stays disabled and the review screen scrolls to the first missing field. Every field in the review has an "Unknown" quick action (sets `UNKNOWN`, `source=USER`).

### 14.6 Unit test matrix (minimum, P3 writes at H2–H4)

| # | Case | Expect |
|---|---|---|
| V1 | Slide example after extraction | missing = [severity, injuries, leak_source, leak_status] |
| V2 | Same + patch injuries=NO, severity=LOW | missing = [leak_source, leak_status]; next q = WATER hazard pair |
| V3 | FIRE + MEDICAL secondary | required includes fire_status, evacuation_status, fire_brigade_called, patient_condition, first_aid_given, ambulance_called |
| V4 | EQUIPMENT without equipment | equipment required and missing |
| V5 | occurred_at in future | invalid FUTURE_TIME, asked again |
| V6 | All required UNKNOWN except type/description | complete = true |
| V7 | askedCount=2 field | not chosen by nextQuestion |
| V8 | CRITICAL | only common fields asked |
| V9 | OTHER category | only common mandatory |
| V10 | `lang=hinglish` | Hinglish template returned |

---

## 15. Normalisers

### 15.1 Time resolver (`TimeResolver`, D-23)

Input: the LLM's English `time` phrase (may still contain Hinglish words), reference `now` = device clock in `Asia/Kolkata`. Output: `ResolvedTime(instant: Instant?, approximate: Boolean, granularity: MINUTE|HOUR|DAYPART|DAY, matchedRule: String)`.

Rules, applied in order (first match wins; all case-insensitive):

| # | Pattern (examples) | Result |
|---|---|---|
| T1 | `just now`, `right now`, `abhi`, `abhi abhi` | now, approx=false |
| T2 | `(\d+|a|an|one|two|…|ek|do|teen|…) (minute|min|hour|hr|ghanta|ghante)s? (ago|back|pehle)` | now − N units, approx = unit is hour |
| T3 | `half an hour ago`, `aadha ghanta pehle` / `thodi der pehle`, `a while ago` | now − 30 min / now − 15 min, approx |
| T4 | Clock time: `10`, `10 am`, `10:30`, `10.30 pm`, `quarter past 10`, `half past 2`, `saadhe das` (10:30), `sawa das` (10:15), `paune das` (9:45), `dedh` (1:30), `dhai` (2:30) | combine with day (T6) |
| T5 | Day-part: `morning/subah` 09:00, `noon/dopahar` 13:00, `afternoon` 15:00, `evening/shaam` 18:30, `night/raat` 22:00, `midnight` 00:00, `late night` 01:00 | approx=true, granularity DAYPART |
| T6 | Day: `today/aaj`, `yesterday/kal` (always past for incidents), `day before yesterday/parson`, `last night` (= yesterday night, or today 00:00–05:00 if now < 06:00), weekday names → most recent past, dates `5th October`, `05/10`, `10-10-2026` | date |
| T7 | Modifiers `around/about/approximately/lagbhag/kareeb/takriban` | approx=true |
| T8 | Hour without AM/PM and no day-part | choose the most recent past occurrence ≤ now (12-hour ambiguity resolved backwards) |
| T9 | Result in the future by > 5 min and no explicit date | subtract one day |
| T10 | Nothing matched | instant=null → field stays `MISSING` (so it will be asked) |

Device clock sanity: if `Year.now() < 2026`, the app shows a red "Device clock is wrong" bar and the resolver still runs (relative times would be wrong; setup §26.3 fixes this). ≥ 40 unit tests (P3).

### 15.2 Location matcher (`LocationMatcher`, D-24)

`config/site_registry.json` (sample in Appendix A.3):
```jsonc
{"version":1,"site":"Demo Plant & Campus","locations":[
  {"id":"A-1-CR2","name":"Conference Room 2","building":"Block A","floor":"1","aliases":["conf room 2","CR2","meeting room 2"]},
  ...]}
```
Algorithm:
1. Normalise the spoken location: lower-case; remove qualifiers (`near, next to, beside, behind, in front of, outside, inside, at, the, ke paas, ke bahar, ke andar, mein, par`) but remember the first qualifier; number words → digits; expand abbreviations (`conf→conference`, `rm→room`, `blk→block`, `flr→floor`).
2. Score against `name` and each `alias`: `max(tokenSetRatio, jaroWinkler)` (implemented in Kotlin, no dependency).
3. ≥ 0.85 → **auto-match**: `location_id` set; display "Conference Room 2 · Block A · Floor 1 (near)".
4. 0.70–0.85 → **suggestion chip** "Did you mean Conference Room 2 (Block A)?" (tap to accept → `USER_EDITED`).
5. < 0.70 → free text only.
The original spoken `location` text is **always** kept.

### 15.3 People and equipment

- `persons`: dedupe by case-insensitive name; unnamed persons kept as `{role, injured}`; `injured_count = max(stated injured_count, count(persons.injured))`.
- Consistency rules: `injuries = NO` but a person `injured = true` → set `injuries = YES` (`source = RULE`) and flag; `injured_count > 0` → `injuries = YES`.
- `equipment`: dedupe; upper-case recognisable asset tags (`p-101` → `P-101`).
- Witnesses are stored as `persons` with role `WITNESS` in the DB (§21) but extracted separately (clearer for the model).

### 15.4 Language detection

Primary: the LLM's `lang`. Fallback (LLM omitted it): Devanagari characters > 20 % → `hi`; else ratio of Hinglish function words (`hai, tha, thi, ki, ka, ke, ko, mein, nahi, aur, kya, se, raha, gaya, hua`) ≥ 0.08 of tokens → `hinglish`; else `en`. Also used to label transcripts in the dashboard.

---
## 16. ASR subsystem (audio → text)

### 16.1 Pipeline

```
Mic ─► AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16) ─► NoiseSuppressor (if available)
    ─► 80 Hz high-pass biquad ─► level meter ─► session ring buffer (whole session PCM, ≤ 3 min)
    ─► Silero VAD v5 (CPU, 32 ms windows) ─► Chunker (≤ 28 s, cut at silences)
    ─► [per chunk] WhisperEngine on HTP: log-mel (CPU, C++) → encoder → language detect → greedy decode
    ─► chunk texts joined ─► hallucination filter ─► transcript (+ lang guess, timings)
```

### 16.2 Audio capture (`AudioCapture`, Kotlin)

| Parameter | Value |
|---|---|
| Source | `MediaRecorder.AudioSource.VOICE_RECOGNITION` (platform pre-processing tuned for ASR) |
| Format | 16 000 Hz, mono, `ENCODING_PCM_16BIT` |
| Frame | 20 ms (320 samples); buffer = max(`getMinBufferSize`, 12 800 bytes) |
| Effects | `NoiseSuppressor.create(sessionId)` if `isAvailable()` (D-16); `AutomaticGainControl` **off**; `AcousticEchoCanceler` **on** if available (TTS tail) |
| Pre-filter | 2nd-order Butterworth high-pass at 80 Hz (removes machine rumble/handling noise) |
| Normalisation | Per-chunk peak normalisation to 0.9 before mel (in C++) |
| Persistence | Full-session PCM written to `files/audio/<incidentId>/<turnIdx>.wav` on `Dispatchers.IO` (D-17) |
| Permission | `RECORD_AUDIO` requested on first mic tap; denial → `ERROR_AUDIO` with a "Open settings" button |

### 16.3 Noise strategy (industrial/outdoor, D-16)

1. `VOICE_RECOGNITION` source + platform `NoiseSuppressor` (zero cost).
2. High-pass 80 Hz.
3. **Hysteresis VAD thresholds** (start 0.6 / end 0.45) so machine noise does not keep the recording alive.
4. Whisper-small is itself robust to noise (AI Hub notes "robust performance in realistic, noisy environments").
5. User guidance in the UI: "Hold the device ~20 cm from your mouth" on first use.
6. P2: RNNoise (CPU, 48 kHz with resampling) only if field tests show the above is not enough.

### 16.4 VAD (`SileroVad`)

| Parameter | Narration | Follow-up answer |
|---|---|---|
| Model | Silero VAD v5 ONNX (16 kHz, 512-sample window, state [2,1,128]) on ONNX Runtime Android CPU, 1 intra-op thread | same |
| Speech start | p ≥ 0.6 for ≥ 250 ms | same |
| Speech end | p < 0.45 for **1 200 ms** (configurable 800–1 500) | **1 000 ms** |
| Pre-roll | 300 ms | 300 ms |
| No-speech timeout | 10 s → "I didn't hear anything" | 8 s → re-prompt once (§11.3) |
| Max length | 180 s (D-13) → auto-stop with a soft beep at 170 s | 30 s |
| Manual stop | Always available | Always |

Fallback if ORT fails to load: energy VAD (RMS > noise floor + 9 dB, floor estimated over the first 500 ms), logged `IV-E102`.

### 16.5 Chunker

- Chunk boundaries are chosen so that no word is split: when speech in the current chunk reaches **20 s**, the next silence ≥ 300 ms ends the chunk; if none occurs by **28 s**, cut at the lowest-energy 100 ms frame within the last 2 s.
- Each completed chunk is immediately queued for transcription on the NPU (O6) while recording continues.
- Chunks with VAD speech ratio < 10 % are skipped (no Whisper call) — prevents hallucinations on noise.
- Chunk texts are joined with a single space.

### 16.6 WhisperEngine (C++, HTP)

**Models:** AI Hub `whisper_small`, precision float, runtime `qnn_context_binary`, device "Samsung Galaxy S25 (Family)", 2 components → `whisper_small_encoder.bin`, `whisper_small_decoder.bin` (renamed at download, §25.4). Encoder input: log-mel `[1, 80, 3000]` (30 s); decoder: one token per step with self-attention KV cache in/out and cross-attention KV from the encoder; max decoded length 200 tokens.

**IO discovery (P1 at H1, mandatory):** dump tensor names, shapes, dtypes and quantisation params with `qnn-context-binary-utility --context_binary <bin> --json_file io.json` (from QAIRT 2.50 `bin/x86_64-linux-clang/`) and commit them as `native/whisper/io_encoder.json`, `io_decoder.json`. The C++ code reads tensor metadata at runtime from the binary (via `QnnSystemContext_getBinaryInfo`), but these files are the reference for code review. The decode loop is a line-by-line port of the Python reference `qai_hub_models/models/_shared/hf_whisper/app.py` (`HfWhisperApp`) from the installed qai-hub-models 0.63 — P1 reads it first.

**Mel (CPU, C++):** Whisper's exact front-end: pad/trim audio to 480 000 samples; STFT `n_fft = 400`, hop 160, periodic Hann window, magnitude²; 80-band mel filterbank (Slaney, from `mel_80.bin` = `librosa.filters.mel(sr=16000, n_fft=400, n_mels=80)` exported by `ml/scripts/export_whisper_assets.py`); `log10(max(x, 1e-10))`; clamp to `max − 8.0`; `(x + 4.0) / 4.0`. FFT via header-only `pocketfft`. Unit-tested against `whisper.log_mel_spectrogram` output (max abs diff < 1e-3).

**Token handling (multilingual vocabulary):**
| Token | id |
|---|---|
| `<\|endoftext\|>` (EOT) | 50257 |
| `<\|startoftranscript\|>` (SOT) | 50258 |
| `<\|en\|>` | 50259 |
| `<\|hi\|>` | 50276 |
| `<\|transcribe\|>` | 50359 |
| `<\|translate\|>` | 50358 (never used) |
| `<\|notimestamps\|>` | 50363 |
| first timestamp token | 50364 |
`[verify H1]` against `tokenizer.json` / `added_tokens` of `openai/whisper-small` while exporting vocab.

**Decode loop:**
```
encode(mel) -> cross_kv
reset self_kv
logits = decoder_step(SOT)                                         // step 0
lang   = (mode == auto_en_hi) ? argmax(logits[{50259, 50276}]) : forced     // restrict to en/hi
for tok in [lang, TRANSCRIBE, NOTIMESTAMPS]: logits = decoder_step(tok)   // prompt steps
out = []
for step in 0 .. 196:
    suppress: all ids ≥ 50364 (timestamps), all special ids except EOT;
              at step 0 also EOT and id 220 (blank)
    t = argmax(logits)
    if t == EOT: break
    out.append(t); logits = decoder_step(t)
    if repeated_ngram(out, n=4, times=3): trim repetition; break
text = bytes_decode(out)                                           // whisper_vocab.bin: id -> raw bytes, UTF-8 join
```
**Hallucination filter:** drop chunk text if it matches known Whisper artefacts (`thank you for watching`, `subtitles by`, `please subscribe`, lone `ॐ`, repeated single character) or if it is < 2 characters.

**Language modes** (`asr.language_mode` in `config/app_config.json`):
| Mode | Behaviour | Expected output for Hinglish speech |
|---|---|---|
| `auto_en_hi` (default) | Detect between en/hi, then transcribe in that language | Often Devanagari (hi) — Llama 3.2 reads Hindi; normalises to English |
| `force_en` | Always `<\|en\|>` | Tends to produce Roman Hinglish or partial English translation |
| `force_hi` | Always `<\|hi\|>` | Devanagari |
The default is decided at **H6** by measuring extraction F1 (not WER) on the Hinglish eval clips in both modes; the winner is written into `app_config.json` and D-10a.

**Outputs:** `AsrResult(text, lang, chunks[{startMs, endMs, text, lang}], timings{melMs, encMs, decMs, tokens})`.

### 16.7 Transcript display and editing (D-15)

Transcript shown as a chat bubble with an "Edit" affordance and a 2-second countdown ring. Extraction starts immediately in the background (O5). If the user edits, the running generation is aborted (`abort()`) and restarted with the edited text; the turn stores both `text_raw` and `text_edited`.

---

## 17. TTS subsystem

### 17.1 Engine

The QIDK has exactly one TTS engine installed: **eSpeak NG** (`com.reecedunn.espeak`) `[verified]`. We use the standard `android.speech.tts.TextToSpeech` API pinned to that package: `TextToSpeech(context, onInit, "com.reecedunn.espeak")`. It runs on the CPU in eSpeak's own process (no NPU contention, O14), fully offline. Quality is robotic but intelligible; F-31 upgrades the voice without code changes.

### 17.2 `TtsSpeaker`

```kotlin
interface TtsSpeaker {
    val ready: StateFlow<Boolean>
    suspend fun speak(text: String, lang: FormLang): SpeakResult   // suspends until onDone/onError/timeout
    fun stop()
}
```
- Voice: locale `en-IN` if `isLanguageAvailable ≥ LANG_AVAILABLE`, else `en-GB`, else default. Rate 0.95, pitch 1.0.
- `AudioAttributes.USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`; request transient audio focus.
- Timeout: `chars × 90 ms + 3 s`; on timeout we proceed as if done.
- After `onDone`: wait **300 ms**, then auto-start listening (D-40). Mic is never open while TTS is speaking (no barge-in, avoids self-transcription).
- If TTS is not ready: show the question in a large card, play a short chime (`ToneGenerator`), auto-start listening anyway.

### 17.3 Language

Questions are rendered in English or Roman Hinglish (D-38) and **both** are spoken with the same `en-IN` eSpeak voice — eSpeak's `hi` voice expects Devanagari and mispronounces Roman text. Numbers inside questions are written as words.

### 17.4 What is spoken (P0)

1. Follow-up questions and re-prompts (§11).
2. "Thank you. I have everything I need." on completion.
3. Emergency banner: "Possible emergency. Call the emergency team now if anyone is in danger." (once).
Recommendations are **not** spoken in P0 (that is O-1).

### 17.5 Upgrade path (F-31, P2)

Install an offline neural TTS engine APK that implements Android's `TextToSpeechService` (e.g. the k2-fsa **sherpa-onnx TTS engine** APK with a Piper/VITS English (Indian accent) voice) and change the engine package in Settings. No app code changes. Runs on CPU.

---

## 18. Native layer (C++ core, JNI, Genie, QNN Whisper)

### 18.1 Layout

```
native/
├── CMakeLists.txt              # targets: iv_core (static), iv_native (JNI .so), iv_npud (executable)
├── third_party/
│   ├── pocketfft_hdronly.hpp
│   └── nlohmann/json.hpp
├── include/iv/
│   ├── npu_scheduler.h         # global mutex + priorities + abort flags
│   ├── llm_engine.h            # Genie wrapper
│   ├── whisper_engine.h        # QNN wrapper + decode loop
│   ├── mel.h                   # log-mel
│   ├── qnn_loader.h            # dlopen libQnnHtp/libQnnSystem, interface tables
│   └── stats.h                 # timing structs, JSON serialisation
├── src/ (…cpp for each header…)
├── jni/iv_jni.cpp              # JNI bindings (Plan A)
├── npud/main.cpp               # JSON-lines worker (Plan B)
└── tests/                      # host-buildable unit tests (mel, BPE decode, JSON) — x86_64
```

The QAIRT headers (`include/QNN/*`, `include/Genie/*`) are **not** committed; CMake reads `-DQAIRT_ROOT=<path to qairt/2.50.x>` (env `QAIRT_ROOT`).

### 18.2 Runtime libraries

| Library | Side | Where (Plan A) | Where (Plan B) |
|---|---|---|---|
| `libGenie.so` | ARM | APK `jniLibs/arm64-v8a` | `/data/local/tmp/iv/lib/` |
| `libQnnHtp.so` | ARM | APK jniLibs | `lib/` |
| `libQnnHtpV79Stub.so` | ARM | APK jniLibs | `lib/` |
| `libQnnSystem.so` (aarch64 build) | ARM | APK jniLibs | `lib/` |
| `libc++_shared.so` | ARM | from NDK automatically | `lib/` |
| `lib/hexagon-v79/unsigned/*` (incl. `libQnnHtpV79Skel.so`, `libQnnHtpV79.so`, and the **Hexagon** `libQnnSystem.so`) | DSP | `models/dsp/` | `/data/local/tmp/iv/dsp/` |

- **Never** mix the aarch64 and Hexagon copies of `libQnnSystem.so` (same name, different binaries — `problems_setup.md` A11). Separate directories, always.
- `packaging { jniLibs { useLegacyPackaging = true } }` so the ARM libs are extracted to `nativeLibraryDir` on install.
- `ADSP_LIBRARY_PATH` (semicolon-separated!) is set with `setenv` in `iv_init()` **before** any QNN/Genie call:
  `"<modelsDir>/dsp;<nativeLibraryDir>;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"`.
- If HTP device creation fails in-app with a protection-domain error, add `"pd_session": "unsigned"` to the device entry in `htp_backend_ext_config.json` (apps run in the unsigned PD on recent Snapdragons) `[verify H6]`.

### 18.3 NpuScheduler

```cpp
class NpuScheduler {
 public:
  enum class Prio { ASR = 0, LLM_FOREGROUND = 1, LLM_BACKGROUND = 2 };
  // Blocks until the NPU is free and no higher-priority job is waiting.
  // If a higher-priority job arrives while a LLM_BACKGROUND job runs, abort_cb of that job is invoked.
  template <class F> auto run(Prio p, F&& job, std::function<void()> abort_cb = {}) -> decltype(job());
  void quiesce_gap_ms(int ms = 600);   // gap after any session teardown (problems_setup §5.3)
};
```
Exactly one instance per process. The Kotlin `npuDispatcher` serialises calls already; the scheduler protects against bugs and handles priority aborts.

### 18.4 LlmEngine (Genie C API)

Lifecycle and calls (names from `include/Genie/GenieDialog.h`, `GenieSampler.h`; `[verify H3]` exact signatures against QAIRT 2.50 headers):

| Step | API | Notes |
|---|---|---|
| Load config | `GenieDialogConfig_createFromJson(json, &cfg)` | JSON = bundle's `genie_config.json` with **paths rewritten** at runtime to absolute paths under the model dir (ctx-bins, tokenizer, extensions). The repo copy keeps relative names. |
| Create dialog | `GenieDialog_create(cfg, &dialog)` | ~2–4 s. Retry policy §31.3 on failure. |
| Warm-up | `GenieDialog_query(dialog, warmupPrompt, GENIE_DIALOG_SENTENCE_COMPLETE, cb, ud)` then `GenieDialog_reset` | "Say OK." prompt. |
| Per-task sampler | `GenieDialog_getSampler(dialog, &sampler)` + `GenieSamplerConfig_createFromJson` + `GenieSampler_applyConfig(sampler, scfg)` | Two prebuilt sampler configs: `greedy` `{"version":1,"seed":42,"temp":1.0,"top-k":1,"top-p":1.0}` and `recommend` `{"version":1,"seed":42,"temp":0.3,"top-k":40,"top-p":0.95}`. **Fallback** if the sampler API is unavailable in 2.50: set greedy in `genie_config.json` and accept greedy for recommendations too (D-33 degraded, logged). |
| Query | `GenieDialog_query(dialog, prompt, GENIE_DIALOG_SENTENCE_COMPLETE, callback, userData)` | Callback receives text pieces with sentence codes BEGIN/CONTINUE/END/ABORT; we forward each piece to Kotlin (JNI callback) or stdout (Plan B). |
| Token cap | `GenieDialog_setMaxNumTokens(dialog, n)` if available; otherwise count callback pieces with the host-side tokenizer estimate and abort at cap | Caps from §13.1. |
| Abort | `GenieDialog_signal(dialog, GENIE_DIALOG_ACTION_ABORT)` | Used by priority preemption and transcript edits. |
| Reset | `GenieDialog_reset(dialog)` | **Before every task call** — tasks are independent; no hidden conversation memory. |
| Prefix cache (P1) | `GenieDialog_save(dialog, path)` / `GenieDialog_restore(dialog, path)` | One saved state per task system prompt (`extract`, `patch`, `recommend`) in `cacheDir/kv/`. Invalidated when the bundle or prompt templates change (hash in filename). Config flag `llm.prefix_cache`. |
| Stats | `GenieProfile_*` bound to dialog, or wall-clock timers | `ttft_ms`, `prefill_tps`, `decode_tps`, `tokens_in`, `tokens_out`. |
| Free | `GenieDialog_free`, `GenieDialogConfig_free` | On app destroy. |

### 18.5 WhisperEngine (QNN API)

| Step | API |
|---|---|
| Load | `dlopen("libQnnHtp.so")` → `QnnInterface_getProviders` → pick provider with API version ≥ 2.x; `dlopen("libQnnSystem.so")` → `QnnSystemInterface_getProviders` |
| Backend/device | `backendCreate(log, cfg, &backend)`; `deviceCreate(log, devCfg, &device)` (HTP, core 0) |
| Contexts | Read `.bin` (mmap); `QnnSystemContext_getBinaryInfo` → graph names + tensor infos; `contextCreateFromBinary(backend, device, cfg, buf, size, &ctx, profile)` for encoder and decoder |
| Graphs | `graphRetrieve(ctx, name, &graph)` |
| Tensors | Allocate client buffers per tensor (dtype from binary info: FLOAT_32/FLOAT_16/UFIXED_16 with scale/offset); conversion helpers fp32↔fp16↔fixed |
| Execute | `graphExecute(graph, inputs, nIn, outputs, nOut, profile, signal)` |
| KV | Decoder self-KV output buffers are swapped into input buffers each step (no copy: ping-pong buffers) |
| Free | `contextFree`, `deviceFree`, `backendFree` |

**Coexistence with Genie in one process** `[verify H5]`: Genie creates its own QNN HTP backend from the same `libQnnHtp.so`. Multiple contexts in one process are normal for QNN; if creating a second backend fails, initialise Whisper's contexts **through Genie's backend** is not possible, so the fallback is: Whisper runs in a **child process** (`iv_npud --asr-only`, Plan-B style, launched from the app via `ProcessBuilder` from `nativeLibraryDir`) — same code, no architecture change for Kotlin (`JniInferenceEngine` delegates `transcribe` to it).

### 18.6 JNI API (Plan A) — `com.megathon.incidentvoice.engine.NativeBridge`

```kotlin
object NativeBridge {
    init { System.loadLibrary("iv_native") }
    external fun init(modelsDir: String, nativeLibDir: String, appConfigJson: String): String      // returns JSON EngineInfo or {"error":...}
    external fun warmup(): String                                                                   // JSON stats
    external fun asrTranscribe(pcm16: ShortArray, sampleRate: Int, langMode: String): String         // JSON AsrResult
    external fun llmGenerate(prompt: String, samplerName: String, maxTokens: Int,
                             cb: TokenCallback): String                                             // JSON GenStats; tokens via cb
    external fun llmAbort()
    external fun llmSaveState(path: String): Boolean                                                // P1 prefix cache
    external fun llmRestoreState(path: String): Boolean
    external fun status(): String                                                                   // JSON: loaded models, qairt version, last error
    external fun release()
}
fun interface TokenCallback { fun onToken(piece: String) }
```
All JSON shapes are defined once in `native/include/iv/stats.h` and mirrored by `@Serializable` Kotlin classes in `:core` (`EngineInfo`, `AsrResult`, `GenStats`, `EngineError`).

### 18.7 Build

- Android: `app/build.gradle.kts` → `externalNativeBuild { cmake { path = file("../../native/CMakeLists.txt"); version = "3.22.1" } }`, `ndk { abiFilters += "arm64-v8a" }`, arguments `-DANDROID_STL=c++_shared -DQAIRT_ROOT=$QAIRT_ROOT -DIV_BUILD_JNI=ON`.
- Plan B worker: `cmake -B build-npud -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_shared -DQAIRT_ROOT=$QAIRT_ROOT -DIV_BUILD_NPUD=ON && cmake --build build-npud -j`.
- Host tests: `cmake -B build-host -DIV_BUILD_TESTS=ON && ctest` (mel, vocab decode, JSON) — no QNN needed.
- Compiler flags: `-O3 -fvisibility=hidden -ffunction-sections -Wl,--gc-sections`; `-DNDEBUG` for release.

---

## 19. Plan B: shell-user inference server (Go) and adb loopback

Only built out fully if the probe fails (D-39). The Go server is small because the C++ worker does the real work.

### 19.1 Processes

```
iv-server (Go, uid shell, 127.0.0.1:8765)
   ├── HTTP API (§19.2), single-flight per resource, SSE streaming
   ├── supervisor: starts iv_npud, health-checks every 5 s, restarts with back-off 1/3/7/15 s
   └── iv_npud (C++ worker, uid shell)  ←→ JSON-lines over stdin/stdout (§19.3)
```

Device layout:
```
/data/local/tmp/iv/
├── bin/iv-server, bin/iv_npud           (chmod 755)
├── lib/   (ARM: libGenie, libQnnHtp, libQnnHtpV79Stub, libQnnSystem, libc++_shared)
├── dsp/   (hexagon-v79/unsigned/*)
├── models/llama/ (bundle), models/whisper/ (enc, dec, vocab, mel)
├── config/ (genie_config.json, htp_backend_ext_config.json, app_config.json)
└── run/   (server.log, worker.log, tmp request files, pid)
```

### 19.2 HTTP API (Plan B)

Base `http://127.0.0.1:8765`, JSON unless stated. Errors: `{"error":{"code":"IV-E6xx","message":"…","retryable":true}}` with HTTP 503 for engine-busy/hardware, 400 for bad input.

| Method & path | Request | Response |
|---|---|---|
| `GET /v1/health` | — | `{"status":"ok|loading|error","models":{"asr":"loaded","llm":"loaded"},"qairt":"2.50.x","uptime_s":123,"worker_restarts":0}` |
| `POST /v1/warmup` | — | `GenStats` |
| `POST /v1/asr/transcribe?lang_mode=auto_en_hi&sr=16000` | body: raw PCM16LE mono (`application/octet-stream`), ≤ 30 s per call (the app sends chunks) | `AsrResult` |
| `POST /v1/llm/generate` | `{"prompt":"…","sampler":"greedy|recommend","max_tokens":320,"stream":true}` | `stream=true`: `text/event-stream` with `event: token` `data: {"piece":"…"}` … `event: done` `data: GenStats`; `stream=false`: `{"text":"…","stats":GenStats}` |
| `POST /v1/llm/abort` | — | `{"aborted":true}` |
| `GET /v1/metrics` | — | `{"thermal":{"nsp_max_c":61.2,"skin_c":38.0},"mem_available_mb":9800,"last":{…GenStats…}}` (reads `/sys/class/thermal/*`, `/proc/meminfo` — the shell user can) |

Security: bind **127.0.0.1 only**; header `X-IV-Token` must match the token in `/data/local/tmp/iv/config/token` (file mode 644; the app reads it once — `/data/local/tmp` is traversable by apps on this build `[verify H6]`; if not readable, the token is passed via `am start --es iv_token …` from the start script).

### 19.3 Worker protocol (JSON-lines)

```
→ {"id":1,"op":"init","models":"/data/local/tmp/iv/models","dsp":"/data/local/tmp/iv/dsp","config":"/data/local/tmp/iv/config/app_config.json"}
← {"id":1,"ok":true,"info":{...EngineInfo...}}
→ {"id":2,"op":"asr","pcm_path":"/data/local/tmp/iv/run/req-2.pcm","sr":16000,"lang_mode":"auto_en_hi"}
← {"id":2,"ok":true,"result":{...AsrResult...}}
→ {"id":3,"op":"generate","prompt_path":"/data/local/tmp/iv/run/req-3.txt","sampler":"greedy","max_tokens":320}
← {"id":3,"tok":"{\"name\""}        (repeated)
← {"id":3,"done":true,"stats":{...GenStats...}}
→ {"id":3,"op":"abort"}
→ {"id":4,"op":"status"}
→ {"id":5,"op":"shutdown"}
```
Large payloads go through files in `run/` (prompt text, PCM) to keep the pipe protocol line-safe.

### 19.4 Starting Plan B

- **MVP (laptop attached):** `scripts/planb_start.ps1` → `adb shell "cd /data/local/tmp/iv && setsid nohup ./bin/iv-server --listen 127.0.0.1:8765 > run/server.log 2>&1 &"`. Survives USB unplug; not reboot.
- **Laptop-free (F-27, P1):** one-time `adb root; adb shell setprop persist.adb.tcp.port 5555; adb unroot` (must be done while root; `persist.` survives reboot). The app's `PlanBLauncher` uses the **dadb** Kotlin library to open an adb connection to `localhost:5555` (no RSA auth on this board) and runs the same start command, then polls `/v1/health`. Re-launches on `BOOT_COMPLETED` (receiver) and when health fails 3×.
- **Security warning (repeat in demo notes):** TCP 5555 = unauthenticated shell for anyone on the same network. Keep Wi-Fi off/isolated during the hackathon; revert with `adb root; adb shell setprop persist.adb.tcp.port ""; adb unroot` afterwards.

### 19.5 Go implementation notes

- Static: `CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o iv-server ./cmd/iv-server`.
- Exec the worker by **absolute path** (bare-name lookup triggers `faccessat2`, killed by seccomp in app contexts — `problems_setup.md` A11).
- On shutdown: send `{"op":"shutdown"}`, wait 1.5 s, then SIGINT, then SIGKILL (releases the HTP cleanly).
- One generation at a time (mutex); ASR requests preempt background generations by sending `abort` first.

---
## 20. Android application

### 20.1 Modules and build flavours

| Module | Type | Contents |
|---|---|---|
| `:core` | Pure Kotlin/JVM library (no Android imports) | Taxonomy model + loader, `IncidentForm`/`FieldState`, `Validator`, `SeverityRules`, `ToolCallParser`, `PartialJson`, `Grounding`, `PromptBuilder`, `TimeResolver`, `LocationMatcher`, `PeopleNormaliser`, `LangDetector`, JSON DTOs mirroring native stats. **All unit tests run on the laptop JVM in seconds.** |
| `:app` | Android application | Compose UI, orchestrator, audio, VAD, TTS, engine implementations, Room DB, exporters, native build (CMake → `../native`). |

| Flavour | `InferenceEngine` bound | Extra permissions | Notes |
|---|---|---|---|
| `planA` (default) | `JniInferenceEngine` | none | No `INTERNET` (D-54). |
| `planB` | `HttpInferenceEngine` | `INTERNET` (loopback) | Adds `PlanBLauncher` (dadb) for F-27. |
| any, debug builds | Settings → Debug → "Engine: Mock" switches to `MockInferenceEngine` at runtime | — | P2's day-one development mode. |

Dependency injection: a hand-written `AppContainer` (no Hilt — fewer build problems in 15 h).

### 20.2 Package layout (`:app`)

```
com.megathon.incidentvoice
├── IncidentVoiceApp.kt            # Application: AppContainer, config load, engine init (background)
├── MainActivity.kt                # single activity, Compose NavHost, FLAG_KEEP_SCREEN_ON during conversation
├── AppContainer.kt
├── config/ConfigLoader.kt         # assets/config/*.json → :core models
├── orchestrator/
│   ├── ConversationOrchestrator.kt   # §8 state machine
│   ├── ConversationState.kt          # sealed states + UiState
│   └── EmergencyDetector.kt          # §10.7 keyword trigger
├── audio/ AudioCapture.kt  HighPass.kt  SileroVad.kt  EnergyVad.kt  Chunker.kt  WavWriter.kt
├── tts/ TtsSpeaker.kt  EspeakTtsSpeaker.kt
├── engine/ InferenceEngine.kt  JniInferenceEngine.kt  HttpInferenceEngine.kt  MockInferenceEngine.kt
│           NativeBridge.kt  EngineStatus.kt  ModelManifest.kt  PlanBLauncher.kt (planB only)
├── data/
│   ├── db/ IncidentDb.kt  Converters.kt  Migrations.kt  FtsCallback.kt
│   ├── entity/ IncidentEntity.kt  PersonEntity.kt  EquipmentEntity.kt  ActionTakenEntity.kt
│   │          RecommendationEntity.kt  TurnEntity.kt  AuditEntity.kt  SecondaryTypeEntity.kt
│   ├── dao/ IncidentDao.kt  TurnDao.kt  StatsDao.kt  SearchDao.kt
│   ├── IncidentRepository.kt
│   └── SearchQueryBuilder.kt
├── export/ PdfExporter.kt  JsonExporter.kt  ShareHelper.kt
└── ui/
    ├── theme/  components/ (SeverityChip, StatusChip, FieldRow, MicButton, LevelMeter, EmergencyBanner, Countdown)
    ├── home/  conversation/  review/  dashboard/  detail/  settings/  debug/
```

### 20.3 Navigation

```
Home ──(mic)──► Conversation ──(complete/manual)──► Review ──(submit)──► Submitted ──► Home
  │                                                    ▲
  ├──► Dashboard ──► Detail ──(status change / export)│
  └──► Settings ──► Debug (engine, eval runner, logs)
Bottom bar: Report · Dashboard · Settings
```

### 20.4 Home screen

- Giant mic button (min 120 dp), label "Tap and describe the incident". Language hint "English or Hinglish".
- Engine status pill: `NPU ready` (green) / `Loading models… 63 %` / `Engine error — tap for details` (red). Mic is disabled until ASR is loaded; typing a report is always possible ("Type instead").
- Resume card for an unfinished draft (< 24 h).
- Last 3 incidents (severity chip, title, relative time).
- Red bar if device clock year < 2026 (§15.1).

### 20.5 Conversation screen

- Top: progress bar = `ValidationResult.progress`; emergency banner slot.
- Middle: chat-style timeline — user transcript bubbles (editable while in `TRANSCRIPT_REVIEW`), assistant question bubbles (with speaker icon while TTS speaks), system notes ("Form updated: location, time").
- Bottom sheet: **live form preview** — fields appear as they stream (shimmer = pending), colours: green `FILLED`, amber `INFERRED`, grey `UNKNOWN`, red outline = required & missing.
- Controls: Mic/Stop (state-aware), "Type instead", "Review now", Cancel (keeps draft).
- Debug overlay (toggle in Settings): per-stage ms, tok/s, backend `HTP V79`, raw tool-call JSON.

### 20.6 Review screen

- Sections: **Summary** (type + secondary chips, severity selector with source badge LLM/RULE/USER, title, description), **When & where** (resolved time with "approx" badge and original phrase; location with registry match chip), **People** (injuries, count, persons list, witnesses), **Category details** (dynamic from taxonomy for all selected categories), **Equipment & actions taken**, **Recommended actions** (streaming list with checkboxes; edit/delete/add), **Reporter** (from profile).
- Every field: tap to edit (typed), per-field mic (P1, D-41), "Unknown" action; required-and-missing fields are outlined red and listed in a sticky "N fields need attention" bar.
- Submit button enabled only when the validator says complete (§14.5).
- Discard → keeps a DRAFT (can be deleted from Dashboard).

### 20.7 Submitted screen

Ref no (e.g. `INC-20261010-0007`), severity, "Share PDF", "Share JSON", "Report another incident", and — if `other_incidents` is non-empty — a card "You also mentioned: *forklift scraped a rack in Bay 4*. Report it now?" which starts a new conversation with that summary pre-filled as the first narration (D-56).

### 20.8 Settings

| Setting | Default | Stored in |
|---|---|---|
| Reporter name, employee ID | empty → prompted on first launch | DataStore |
| ERT phone number | `112` | DataStore |
| VAD end-of-speech silence | 1 200 ms | DataStore |
| ASR language mode | from `app_config.json` | DataStore override |
| TTS engine package | `com.reecedunn.espeak` | DataStore |
| Speak questions | on | DataStore |
| Show debug overlay | off | DataStore |
| Engine (debug builds) | JNI / HTTP / Mock | DataStore |
| Model status | read-only: files present, sizes OK, QAIRT version, load times, "Re-run warm-up" | — |
| Site registry | read-only list (P2: import JSON) | assets / DB |
| Eval runner (P1) | §29.5 | — |
| Export logs | zips `files/logs/` → share | — |

### 20.9 Manifest essentials

```xml
<manifest package="com.megathon.incidentvoice">
  <uses-permission android:name="android.permission.RECORD_AUDIO"/>
  <!-- planB flavour manifest adds: <uses-permission android:name="android.permission.INTERNET"/> -->
  <queries>
    <intent><action android:name="android.intent.action.TTS_SERVICE"/></intent>   <!-- TTS engine visibility (API 30+) -->
    <intent><action android:name="android.intent.action.DIAL"/></intent>
  </queries>
  <application android:name=".IncidentVoiceApp" android:extractNativeLibs="true"
               android:allowBackup="false" android:largeHeap="true" ...>
    <uses-native-library android:name="libcdsprpc.so" android:required="false"/>   <!-- vendor FastRPC for NPU -->
    <activity android:name=".MainActivity" android:exported="true" android:screenOrientation="portrait"
              android:windowSoftInputMode="adjustResize"> … MAIN/LAUNCHER … </activity>
    <provider android:name="androidx.core.content.FileProvider"
              android:authorities="com.megathon.incidentvoice.files" android:exported="false"
              android:grantUriPermissions="true"> <meta-data … @xml/file_paths/> </provider>
  </application>
</manifest>
```
`minSdk 31`, `targetSdk 35`, `compileSdk 35`, `abiFilters arm64-v8a` (D-62).

### 20.10 Assets and model files

| What | Where | How it gets there |
|---|---|---|
| `config/*.json`, `config/prompts/*` | APK `assets/config/` | Gradle `copyConfig` task copies `<repo>/config` → `app/src/main/assets/config` before `preBuild` (single source of truth). |
| Silero VAD ONNX (~2 MB) | APK `assets/models/silero_vad.onnx` | committed in `android/app/src/main/assets/models/` |
| Llama bundle, Whisper bins, vocab, mel, DSP libs | `/sdcard/Android/data/com.megathon.incidentvoice/files/models/{llama,whisper,dsp}/` (Plan A) | `scripts/push_models.ps1` (adb push) after first install |
| `MANIFEST.json` (names, sizes, sha256) | same dir | Generated by `scripts/make_manifest.py` at export time; app verifies sizes at startup and sha256 lazily in the background; mismatch → Model status red with the file name |

Model files are **not** in the APK (4 GB). Uninstalling the app deletes them — the push script is idempotent and fast over USB 3 (~30 s for 4 GB).

### 20.11 Startup sequence

1. `Application.onCreate`: load config (assets) → open DB → start `EngineInitializer` on `npuDispatcher`.
2. Engine init: verify model manifest (sizes) → `NativeBridge.init` → load Whisper → 600 ms gap → load Llama (Genie dialog) → warm-up → (P1) build prefix caches. Progress reported to `EngineStatus` StateFlow (Home pill).
3. Failures follow §31.3 (3/7/15 s retries), then `ERROR_ENGINE` state with "Retry" and "Type report manually".

### 20.12 Dependencies (Gradle)

`androidx.compose.*` (BOM), `androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.room:room-runtime/ktx/compiler(ksp)` **2.7.x**, `androidx.sqlite:sqlite-bundled`, `androidx.datastore:datastore-preferences`, `org.jetbrains.kotlinx:kotlinx-serialization-json`, `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `com.microsoft.onnxruntime:onnxruntime-android` (VAD), `com.patrykandpatrick.vico:compose-m3` (charts), planB: `com.squareup.okhttp3:okhttp` + `okhttp-sse`, `dev.mobile:dadb`. Tests: JUnit5/4, kotlinx-coroutines-test, Turbine, Robolectric (optional).

---

## 21. Data model and storage

### 21.1 Storage locations

| Data | Location | Notes |
|---|---|---|
| SQLite DB | `/data/data/com.megathon.incidentvoice/databases/incidents.db` (app-private) | Room 2.7 + `BundledSQLiteDriver` (FTS5 available regardless of the platform SQLite build). |
| Audio | `filesDir/audio/<incidentId>/<turnIdx>.wav` | 16 kHz mono PCM16 (D-17). |
| Exports | `cacheDir/exports/` | Deleted after 24 h. |
| Logs | `filesDir/logs/iv-YYYYMMDD.log` | Rotated, 5 × 2 MB. |
| KV prefix caches | `cacheDir/kv/<task>-<hash>.bin` | P1. |
| Settings | DataStore `settings.preferences_pb` | |

### 21.2 Entity-relationship overview

```
incidents 1───* incident_secondary_types
incidents 1───* persons            (roles incl. WITNESS)
incidents 1───* equipment
incidents 1───* actions_taken
incidents 1───* recommendations
incidents 1───* turns              (narration, questions, answers, corrections, with audio paths + timings)
incidents 1───* audit_log
incident_search (FTS5, one row per incident, rowid-linked via incident_rowid)
```

### 21.3 Tables (summary — complete DDL in Appendix E)

**`incidents`**
| column | type | notes |
|---|---|---|
| `rowid_pk` | INTEGER PK AUTOINCREMENT | internal; FTS link |
| `id` | TEXT UNIQUE NOT NULL | UUID |
| `ref_no` | TEXT UNIQUE | `INC-YYYYMMDD-NNNN`, assigned on submit |
| `status` | TEXT NOT NULL | `DRAFT, SUBMITTED, ACKNOWLEDGED, RESOLVED, CLOSED` (D-46) |
| `incident_type` | TEXT | category code |
| `title`, `description` | TEXT | |
| `severity` | TEXT | `LOW..CRITICAL` |
| `severity_source` | TEXT | `LLM, RULE, USER` |
| `location_text` | TEXT | as spoken (English) |
| `location_id` | TEXT | registry id or NULL |
| `occurred_at` | INTEGER | UTC epoch ms, NULL if UNKNOWN |
| `occurred_at_approx` | INTEGER (0/1) | |
| `time_expression` | TEXT | raw phrase |
| `injuries` | TEXT | `YES, NO, UNKNOWN` |
| `injured_count` | INTEGER | |
| `near_miss` | INTEGER (0/1) | |
| `hazard_ongoing` | TEXT | |
| `details_json` | TEXT | category fields `{key: value}` |
| `field_meta_json` | TEXT | `{key: {status, source, turnId, grounded, askedCount}}` |
| `other_incidents_json` | TEXT | string array |
| `lang` | TEXT | `en, hinglish, hi` |
| `reporter_name`, `reporter_emp_id` | TEXT | from profile |
| `emergency_flag` | INTEGER | 0/1 |
| `followup_rounds` | INTEGER | |
| `created_at`, `updated_at`, `submitted_at`, `reported_at` | INTEGER | epoch ms |
| `model_versions_json` | TEXT | `{"asr":"whisper_small@aihub-2.50","llm":"llama32-3b-w4a16-stock|ft-v1","taxonomy":1,"prompts":"<hash>"}` |
| `sync_state` | TEXT DEFAULT `'LOCAL_ONLY'` | reserved for O-3; unused in P0 |

Indexes: `(status, created_at DESC)`, `(incident_type)`, `(severity)`, `(occurred_at)`.

**`turns`**: `id`, `incident_id` FK, `idx`, `role` (`USER, ASSISTANT, SYSTEM`), `kind` (`NARRATION, FOLLOWUP_Q, FOLLOWUP_A, FIELD_EDIT, CORRECTION, NOTE`), `asked_fields_json`, `text_raw`, `text_edited`, `lang`, `audio_path`, `audio_ms`, `asr_ms`, `llm_ms`, `llm_tool_json` (raw tool call), `llm_stats_json`, `created_at`.

**`persons`**: `id`, `incident_id`, `name`, `role` (`AFFECTED, INVOLVED, RESPONDER, REPORTER, WITNESS, OTHER`), `injured` (0/1), `injury`, `contact`.

**`equipment`**: `id`, `incident_id`, `name`, `asset_tag`.

**`actions_taken`**: `id`, `incident_id`, `ord`, `text`.

**`recommendations`**: `id`, `incident_id`, `ord`, `sop_id` (`WATER-01` / `CUSTOM` / `USER`), `text`, `source` (`SOP_TAILORED, SOP_TEMPLATE, CUSTOM, USER`), `done` (0/1), `immediate` (0/1).

**`incident_secondary_types`**: `incident_id`, `type`, PK(both).

**`audit_log`**: `id`, `incident_id`, `at`, `actor` (`SYSTEM, LLM, USER:<emp_id>`), `action` (`CREATE, FIELD_SET, STATUS_CHANGE, SUBMIT, EXPORT, DELETE`), `field`, `old_value`, `new_value`.

### 21.4 Full-text search (FTS5)

```sql
CREATE VIRTUAL TABLE incident_search USING fts5(
  body,
  incident_id UNINDEXED,
  tokenize = 'unicode61 remove_diacritics 2',
  prefix = '2 3'
);
```
- One row per incident, maintained by `IncidentRepository.reindex(incidentId)` after every save (not triggers — the body is assembled in Kotlin).
- **Body** = `ref_no, title, description, location_text, registry location name/building, category labels (primary + secondary) + their Hinglish cue words (e.g. FIRE → "fire aag smoke dhuan"), severity, status, all turn texts (raw + edited), persons names, equipment, actions_taken, recommendations, details values, reporter_name`.
- Room cannot validate FTS5 at compile time → queries go through `@RawQuery(observedEntities = [IncidentEntity::class])`.
- Fallback if the bundled driver causes build issues: a Room `@Fts4` entity with the same body column (platform SQLite supports FTS4); only ranking changes (recency ordering is the default anyway).

### 21.5 Query patterns

```sql
-- Recency list with filters (default dashboard)
SELECT * FROM incidents
 WHERE status != 'DRAFT' AND (:types IS NULL OR incident_type IN (:types)) AND (:sev IS NULL OR severity IN (:sev))
   AND (:status IS NULL OR status IN (:status)) AND created_at BETWEEN :from AND :to
 ORDER BY created_at DESC LIMIT :limit OFFSET :offset;

-- Keyword search (recency order; 'Best match' toggle swaps ORDER BY to bm25(incident_search))
SELECT i.*, snippet(incident_search, 0, '[', ']', '…', 10) AS snip
  FROM incident_search s JOIN incidents i ON i.id = s.incident_id
 WHERE incident_search MATCH :ftsQuery AND <same filters>
 ORDER BY i.created_at DESC LIMIT :limit;
```
`SearchQueryBuilder`: split user text on non-letter/digit (Unicode-aware) → drop tokens < 2 chars → escape quotes → `"tok"*` per token joined by spaces (implicit AND). Example: `water conf` → `"water"* "conf"*`.

### 21.6 Retention and integrity

- Drafts older than 7 days are purged on startup (audio included), after a confirmation toast.
- Submitted incidents are never auto-deleted. Manual delete (Detail → ⋮ → Delete) writes an audit entry and removes audio.
- All multi-row writes (form save + children + FTS reindex + audit) happen in **one transaction** (`withTransaction`).
- Schema version 1; `Migrations.kt` holds future migrations; `exportSchema = true` to `android/app/schemas/`.

---

## 22. Interfaces and endpoints

### 22.1 `InferenceEngine` (Kotlin, `:app/engine`)

```kotlin
interface InferenceEngine {
    val status: StateFlow<EngineStatus>                 // Loading(progress), Ready(info), Error(code, msg, retryable)
    suspend fun init(): EngineInfo
    suspend fun warmup(): GenStats
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int = 16_000, mode: LangMode): AsrResult
    fun generate(req: GenRequest): Flow<GenEvent>       // GenEvent.Token(piece) … GenEvent.Done(stats) | GenEvent.Error(err)
    suspend fun abort()
    suspend fun release()
}
data class GenRequest(val task: LlmTask, val prompt: String, val sampler: SamplerName, val maxTokens: Int)
enum class LlmTask { EXTRACT, PATCH, RECOMMEND, REPAIR, WARMUP }
enum class SamplerName { GREEDY, RECOMMEND }
enum class LangMode { AUTO_EN_HI, FORCE_EN, FORCE_HI }
```
Implementations: `JniInferenceEngine` (wraps `NativeBridge`, runs on `npuDispatcher`), `HttpInferenceEngine` (OkHttp + SSE to §19.2), `MockInferenceEngine` (canned responses from `assets/mock/*.json`, configurable delays to mimic real latency: ASR 700 ms, 28 tok/s streaming).

### 22.2 Orchestrator API (UI ↔ domain)

```kotlin
class ConversationOrchestrator(...) {
    val ui: StateFlow<ConversationUiState>
    fun startNew(prefillNarration: String? = null)
    fun resumeDraft(incidentId: String)
    fun onMicTapped(); fun onStopTapped(); fun onTypeInstead(text: String)
    fun onTranscriptEdited(text: String); fun onContinue()
    fun onReviewNow(); fun onFieldEdited(key: String, value: JsonElement?); fun onFieldUnknown(key: String)
    fun onFieldVoiceEdit(key: String)                    // P1
    fun onRecommendationEdited(list: List<RecommendationUi>)
    fun onSubmit(); fun onCancel()
    fun onEmergencyCall(); fun onEmergencyDismiss()
}
```

### 22.3 Repository API

```kotlin
interface IncidentRepository {
    suspend fun createDraft(reporter: Reporter): String                       // returns incidentId
    suspend fun saveForm(id: String, form: IncidentForm, actor: Actor)        // tx: incident + children + audit + reindex
    suspend fun appendTurn(id: String, turn: TurnRecord): Long
    suspend fun saveRecommendations(id: String, recs: List<Recommendation>)
    suspend fun submit(id: String): String                                    // assigns ref_no, status SUBMITTED
    suspend fun setStatus(id: String, status: IncidentStatus, actor: Actor)
    suspend fun delete(id: String, actor: Actor)
    fun observeList(filter: DashboardFilter, page: Int): Flow<List<IncidentListItem>>
    fun search(text: String, filter: DashboardFilter, bestMatch: Boolean): Flow<List<IncidentListItem>>
    fun observeDetail(id: String): Flow<IncidentDetail?>
    suspend fun stats(range: DateRange): DashboardStats
    suspend fun latestDraft(maxAge: Duration): String?
    suspend fun purgeOldDrafts(maxAge: Duration): Int
}
```

### 22.4 Native / HTTP / worker

- JNI: §18.6. HTTP (Plan B): §19.2. Worker protocol: §19.3. All three carry the same JSON DTOs (`EngineInfo`, `AsrResult`, `GenStats`, `EngineError`) defined in Appendix D.5.

### 22.5 Config files (read-only at runtime)

| File | Schema owner | Consumers |
|---|---|---|
| `config/taxonomy.json` | P3 | `:core` (validator, parser, prompt catalogue, questions), DB labels, `ml/` data generator, `eval/` scorer |
| `config/sop.json` | P3 | recommend prompt, emergency banner, `ml/` |
| `config/site_registry.json` | P3 | `LocationMatcher`, data generator (realistic locations) |
| `config/app_config.json` | P1 | engine (`asr.language_mode`, token caps, sampler configs, `llm.prefix_cache`, VAD defaults, thermal thresholds) |
| `config/prompts/*.txt`, `*.jsonl`, `golden/*` | P3 | `PromptBuilder` (Kotlin + Python) |

`app_config.json` (P0 content):
```json
{
  "version": 1,
  "asr": {"language_mode": "auto_en_hi", "max_chunk_s": 28, "min_speech_ratio": 0.1},
  "vad": {"start_threshold": 0.6, "end_threshold": 0.45, "end_silence_ms": 1200, "followup_end_silence_ms": 1000,
          "preroll_ms": 300, "no_speech_timeout_ms": 10000, "followup_no_speech_timeout_ms": 8000, "max_narration_s": 180},
  "llm": {"prefix_cache": false,
          "max_tokens": {"extract": 320, "patch": 120, "recommend": 260, "repair": 320},
          "samplers": {"greedy": {"version":1,"seed":42,"temp":1.0,"top-k":1,"top-p":1.0},
                       "recommend": {"version":1,"seed":42,"temp":0.3,"top-k":40,"top-p":0.95}},
          "recommend_timeout_ms": 12000},
  "conversation": {"max_followup_rounds": 3, "transcript_autoproceed_ms": 2000, "tts_post_gap_ms": 300},
  "thermal": {"defer_background_above_c": 88, "skin_warn_above_c": 60},
  "models": {"llama_dir": "llama", "whisper_dir": "whisper", "dsp_dir": "dsp"}
}
```

---

## 23. Dashboard

### 23.1 Layout

```
┌───────────────────────────────────────────────┐
│ 🔍 Search incidents…                 [Best ▢] │
│ [Type ▾] [Severity ▾] [Status ▾] [Last 7d ▾]  │
├───────────────────────────────────────────────┤
│  Today 4   │  Open 6   │  Critical (7d) 1     │   ← stat cards
│  ▇▇▅▃▂▅▇  incidents per day (7d)               │   ← bar chart
│  by type (stacked bars) · by severity (donut)  │
├───────────────────────────────────────────────┤
│ ● HIGH  🔥 Fire near DB-3 panel        10:42   │
│   Block B · Floor 2 · SUBMITTED                │
│ ● LOW   💧 Water leak near Conf Room 2  10:05  │
│   …                                            │
└───────────────────────────────────────────────┘
```
- Default order: **most recent first** (`created_at DESC`) — RA.txt "recency based".
- Search: debounced 250 ms, FTS5 prefix query, matched snippet shown under the title with `[highlights]`.
- Filters: multi-select chips; date presets Today / 7 days / 30 days / All / custom range.
- Stats card row and charts respect the active filters/date range (Vico charts). Collapsible on scroll.
- Pull-to-refresh is not needed (Flow-backed); paging 50 rows per page.
- Long-press list item → quick status change.

### 23.2 Detail view (P0 basic, P1 complete)

P0: all fields (read-only with "Edit" → opens Review in edit mode), transcript turns, recommendations checklist (tick `done`), status buttons (Acknowledge / Resolve / Close, D-46), share PDF/JSON (P1).
P1: audio playback per turn (`MediaPlayer`), audit log list, raw tool-call JSON (debug).

### 23.3 Stats queries

`StatsDao`: `countByDay(from,to)`, `countByType(from,to)`, `countBySeverity(from,to)`, `countOpen()` (status in SUBMITTED/ACKNOWLEDGED), `countCritical(from,to)`. All exclude DRAFT.

---

## 24. Report export (PDF / JSON, D-42)

### 24.1 PDF (`PdfExporter`, P1)

`android.graphics.pdf.PdfDocument`, A4 (595 × 842 pt), no external library. Sections: header (IncidentVoice, ref no, status, severity colour band), summary table, when/where, people, category details, actions taken, recommended actions (with done ticks), transcript (all turns, original + English where different), reporter + timestamps, footer "Generated on-device by IncidentVoice · models: … · page n/N". Auto page breaks. Saved to `cacheDir/exports/<ref_no>.pdf`, shared via `FileProvider` + `ACTION_SEND` (`application/pdf`).

### 24.2 JSON (`JsonExporter`, P1)

Schema `incident_report.v1` (Appendix D.4): the full incident with resolved fields, field meta, persons, recommendations, turns (without audio), model versions. Pretty-printed, UTF-8, `<ref_no>.json`, shared the same way. The JSON export is also the format used by the evaluation scorer (§29), so one schema serves both.

---
## 25. Model acquisition and export

All exports start at **hour 0** (D-61). The order and owners are fixed so nobody waits.

| # | Artifact | Where it is built | Owner | Start | Expected ready |
|---|---|---|---|---|---|
| E1 | QAIRT 2.50 SDK download | Laptop (QPM / Software Center) | P1 | H0:00 | H0:45 |
| E2 | Llama 3.2 3B stock Genie bundle (w4a16, V79) | **Kaggle** notebook (primary) | P1 | H0:10 | H3:00–H5:00 |
| E3 | Whisper-small encoder/decoder context binaries | Laptop WSL (`~/venvs/qaihm`) | P1 | H0:20 | H1:15 |
| E4 | Whisper assets (`mel_80.bin`, `whisper_vocab.bin`) | Laptop WSL | P1 | H0:40 | H1:00 |
| E5 | Silero VAD v5 ONNX | Laptop (download from the official repo release) | P2 | H0:30 | H0:35 |
| E6 | Fine-tuned Llama bundle (stretch) | Kaggle (train + export) | P3 → P1 | H4:00 | H11:00 |
| E7 | Fine-tuned Whisper (stretch) | Laptop GPU | P3 | H6:00 | H11:00 |

### 25.1 QAIRT 2.50 SDK (E1)

1. Log in to Qualcomm (the same Qualcomm ID as AI Hub).
2. Either the QPM CLI (in WSL):
   ```bash
   qpm-cli --login <qualcomm-id>
   qpm-cli --license-activate qualcomm_ai_runtime_sdk
   qpm-cli --extract qualcomm_ai_runtime_sdk          # choose the 2.50.x build matching AI Hub (see below)
   ```
   or the Software Center web download of the "Qualcomm AI Runtime (QAIRT) SDK" 2.50.x zip `[verify H0 which is faster]`.
3. Unpack to WSL `~/qairt/2.50.x/` and export `QAIRT_ROOT=~/qairt/2.50.x`.
4. Copy only what Windows/Android Studio needs to `C:\qairt\2.50.x\` (≈ 300 MB): `include/`, `lib/aarch64-android/`, `lib/hexagon-v79/`, `bin/aarch64-android/`, `bin/x86_64-windows-msvc/` (for `qnn-context-binary-utility` on Windows if present). Set Windows env `QAIRT_ROOT=C:\qairt\2.50.x`.
5. **Version rule:** the device libs (from this SDK) must be **≥** the QAIRT version recorded in each exported bundle's `tool-versions.yaml`. AI Hub's current default is 2.50.x (S25 perf entries show `2.50.0.260828221209`). If AI Hub's compile version is newer than the SDK we downloaded, pin the compile: `--compile-options "--qairt_version 2.50"`.

### 25.2 Llama 3.2 3B stock bundle on Kaggle (E2)

Why Kaggle: 30 GB RAM, fast network, no laptop swap/disk/uplink pressure (laptop export was 4–6 h on the previous project because of a ~1.3 MB/s uplink and swap thrashing — `MIDEVAL_RUNBOOK.md`).

Notebook `ml/export/kaggle_llama_export.ipynb` (Internet ON; Secrets: `HF_TOKEN`, `QAIHUB_TOKEN`; accelerator: none/CPU is fine):
```bash
pip install -q "qai-hub-models==0.63.0"
qai-hub-models install llama_v3_2_3b_instruct            # pulls the pinned torch/transformers/onnx/aimet deps
qai-hub configure --api_token "$QAIHUB_TOKEN"
huggingface-cli login --token "$HF_TOKEN"                # Llama is gated: licence must be accepted on HF first
df -h /kaggle/temp /kaggle/working; free -g              # need ~50 GB temp disk; record RAM
qai-hub-models export llama_v3_2_3b_instruct \
  --device "Samsung Galaxy S25 (Family)" \
  --target-runtime genie \
  --skip-profiling \
  --output-dir /kaggle/temp/genie_stock \
  --zip-assets
cp /kaggle/temp/genie_stock/*.zip /kaggle/working/       # ~2.7 GB; download via UI or `kaggle kernels output`
```
Notes:
- `--target-runtime genie` is mandatory (default `geniex_qairt` emits no `genie_config.json`).
- The qai-hub CLI has no `compile` subcommand — always use `qai-hub-models export`.
- Expected output: `llama_v3_2_3b_instruct_part_{1,2,3}_of_3.bin`, `genie_config.json`, `htp_backend_ext_config.json`, `tokenizer.json`, `sample_prompt.txt`, `tool-versions.yaml`.
- Monitor jobs on `workbench.aihub.qualcomm.com`; the CLI prints job URLs.

**Fallback ladder (decide by H1:30 based on Kaggle progress/RAM):**
| Level | Action | Cost |
|---|---|---|
| L1 | Kaggle stock 3B export (above) | primary |
| L2 | Same export on the laptop WSL (9 GB RAM + 64 GB swap; requires the VHDX compaction from Appendix H so the swap file fits on C:) | 4–6 h, laptop hot |
| L3 | `llama_v3_2_1b_instruct` export on Kaggle (half the memory/upload) | lower extraction accuracy |
| L4 | Board CPU: llama.cpp `Llama-3.2-3B-Instruct-Q4_K_M.gguf` behind the same `InferenceEngine` (new `LlamaCppEngine`) | works offline but **not NPU** — judging criterion 1 weakened; last resort only |

### 25.3 Bundle verification (mandatory, every download)

```bash
python ml/scripts/verify_bundle.py <bundle_dir>
```
The script checks: (a) every `.bin` header `[u64 header_size][u64 blob_size]` sums to the file size (catches truncated downloads — a real failure on the previous project), (b) writes/compares `SHA256SUMS.txt`, (c) `tool-versions.yaml` QAIRT ≤ device SDK version, (d) `htp_backend_ext_config.json` has `dsp_arch: "v79"` and the `soc_model` AI Hub chose, (e) `genie_config.json` rope-scaling `factor == 32.0`, context size 4096, `bos-token 128000`, `eos-token` contains 128009, (f) ctx-bin names in `genie_config.json` exist. Then generates `MANIFEST.json` (§20.10).

### 25.4 Whisper-small context binaries (E3, E4)

```bash
source ~/venvs/qaihm/bin/activate
qai-hub-models install whisper_small                    # if extra deps are needed
qai-hub-models export whisper_small \
  --target-runtime qnn_context_binary \
  --device "Samsung Galaxy S25 (Family)" \
  --skip-profiling --skip-inferencing \
  --output-dir ~/iv_exports/whisper_small
# rename the two outputs:
#   *encoder*.bin -> whisper_small_encoder.bin ; *decoder*.bin -> whisper_small_decoder.bin
python ml/scripts/export_whisper_assets.py --model openai/whisper-small --out ~/iv_exports/whisper_small
#   writes mel_80.bin (float32 [80][201]) and whisper_vocab.bin (u32 n; repeated {u16 len, bytes}) and token_ids.json
$QAIRT_ROOT/bin/x86_64-linux-clang/qnn-context-binary-utility --context_binary whisper_small_encoder.bin --json_file io_encoder.json
$QAIRT_ROOT/bin/x86_64-linux-clang/qnn-context-binary-utility --context_binary whisper_small_decoder.bin --json_file io_decoder.json
```
Upload is ~1 GB (fp32 ONNX) → ~15 min on the slow uplink. The S25 profile in `perf.yaml` used `precompiled_qnn_onnx`; `qnn_context_binary` is the same compiled graph in raw form `[inferred]`. If `qnn_context_binary` export fails for this model, use `precompiled_qnn_onnx` and extract the embedded context binary (ONNX `EPContext` node attribute `ep_cache_context`) with `ml/scripts/extract_epcontext.py`.

### 25.5 Laptop-side model staging

```
C:\iv_models\
├── llama\        (3 .bin, tokenizer.json, genie_config.json, htp_backend_ext_config.json, tool-versions.yaml, SHA256SUMS.txt)
├── llama_ft\     (stretch)
├── whisper\      (whisper_small_encoder.bin, whisper_small_decoder.bin, mel_80.bin, whisper_vocab.bin, token_ids.json)
├── dsp\          (copy of $QAIRT_ROOT\lib\hexagon-v79\unsigned\*)
└── MANIFEST.json
```
Kept outside the git repo (`.gitignore` covers `*.bin`, `iv_models/`, `_board_runtime_backup/`).

### 25.6 Fallback if QAIRT 2.50 cannot be obtained

Compile everything with `--compile-options "--qairt_version 2.45"` and use the board's backed-up **2.47** runtime (`_board_runtime_backup/`: `libGenie.so`, `libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so`, `libQnnSystem.so`, `genie-t2t-run`, `qnn-net-run`). Without SDK headers the JNI path is blocked, so this forces **Plan B-CLI**: the Go server calls `genie-t2t-run` per request (cold start ~1–3 s per call) and `qnn-net-run` for Whisper. Functional, slower; documented as the emergency path only.

---

## 26. Device setup and deployment

### 26.1 Laptop toolchain (H0, P2 drives, in parallel with exports)

1. Android Studio (Ladybug+), then SDK Manager: Android 15 (API 35) platform, Build-Tools 35.x, **NDK 27.2.12479018 (r27c)**, CMake 3.22.1, Platform-Tools. Uses its bundled JBR 17.
2. **One adb only.** After installing Android Studio, all scripts use `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`; kill any other adb (`taskkill /IM adb.exe /F`) before switching. Two adb versions fight over port 5037 (`could not read ok from ADB Server`). `scripts/env.ps1` defines `$ADB`.
3. Go 1.22+ (only if Plan B), Python 3.11 venvs already in WSL.
4. Teammates' laptops: Android Studio for building; the **board stays on the integration laptop** (this one). Others hand over APKs (shared folder/USB) or push branches for the integration laptop to build.

### 26.2 Board inventory (H0:15)

`scripts/board_inventory.ps1` → `docs/board_inventory.txt`: `getprop` (platform, soc, build type, debuggable, adb.secure, fingerprint), `/proc/meminfo`, `df -h /data`, `/dev/fastrpc*`, `getenforce`, `date`, TTS engines, thermal zone names. Compare with §6.1; any difference → update §6.1.

### 26.3 Clock, timezone, housekeeping (H0:20)

```powershell
& $ADB root
& $ADB shell "setprop persist.sys.timezone Asia/Kolkata"
$now = Get-Date
& $ADB shell ("date " + $now.ToString("MMddHHmmyyyy.ss"))     # toybox: MMDDhhmm[CCYY][.ss]
& $ADB unroot
& $ADB shell date                                            # verify IST, correct year
```
The RTC may reset after reboot (board booted in 2024) → `scripts/fix_clock.ps1` is part of `restore_board.ps1`, and the app shows a red bar if the year < 2026.
Also: `adb shell settings put global stay_on_while_plugged_in 7` (screen stays on while powered), `adb shell svc wifi disable` during the demo (D-54, §32.3).

### 26.4 Push runtime and models

Plan A target: `/sdcard/Android/data/com.megathon.incidentvoice/files/models/` (exists after the first APK install + launch; the script creates it otherwise with `mkdir -p`).
Plan B target: `/data/local/tmp/iv/` (§19.1).

`scripts/push_models.ps1 -Plan A|B`:
- Uses `Get-ChildItem … | ForEach-Object { & $ADB push $_.FullName $dest }` (PowerShell does not expand `*` for adb — a real failure last time).
- Skips files whose remote size (`adb shell stat -c %s`) already matches.
- Pushes `C:\iv_models\{llama,whisper,dsp}` and `MANIFEST.json`.
- Plan B additionally pushes `lib/` (ARM runtime), `bin/iv-server`, `bin/iv_npud`, `config/`, and `chmod 755`.

### 26.5 Smoke tests (run in this order; each must pass before the next)

| # | Test | Command / action | Pass criteria |
|---|---|---|---|
| S0 | Runtime libs load | `genie-t2t-run` (2.50, from `$QAIRT_ROOT/bin/aarch64-android`) pushed to `/data/local/tmp/iv/bin/` with `LD_LIBRARY_PATH=/data/local/tmp/iv/lib`, run with no args | Prints usage, no missing-library error |
| S1 | Llama on NPU ("Say OK") | `scripts/smoke_llm.ps1`: `genie-t2t-run -c <genie_config with absolute paths> --prompt_file p.txt` as shell, `ADSP_LIBRARY_PATH="<dsp>;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp"` | `[BEGIN]: OK…[END]`, no 14001/1002 |
| S2 | BOS parity + speed | same with `--profile` on the slide example prompt; compare token count with `ml/scripts/count_tokens.py` | count = host + 1 (§13.9); decode ≥ 20 tok/s |
| S3 | Greedy determinism | run extract prompt twice with greedy sampler config | identical output |
| S4 | Whisper on NPU (shell) | `iv_npud --asr-test /data/local/tmp/iv/test/slide_example.wav` | transcript ≈ slide sentence; encoder < 150 ms |
| S5 | App probe (§26.6) | Debug → NPU probe | see decision rule |
| S6 | App end-to-end | demo scene 1 | form complete, ≤ 8 s first pass |

### 26.6 The Plan A probe (decides D-39)

`android-probe/` is a ~150-line throwaway app P1 builds at **H2–H3**, independent of models being ready:
1. `System.loadLibrary("probe")` (links `libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnSystem.so` from jniLibs; skel pushed to its `files/dsp/`; manifest has `<uses-native-library libcdsprpc.so>`).
2. Sets `ADSP_LIBRARY_PATH`, calls `QnnInterface_getProviders` → `backendCreate` → `deviceCreate`.
3. If E3 is ready: `contextCreateFromBinary(whisper_small_encoder.bin)` and one `graphExecute` with zeros.
4. Shows PASS/FAIL + error code; logcat tag `IVPROBE`.

Decision rule:
- `deviceCreate` + `contextCreateFromBinary` + execute OK → **Plan A** (record D-39a, delete nothing; Plan B code stays in the repo, unused).
- Error `14001` and logcat `open_device_node … Permission denied` → **Plan B**. Do not spend time defeating it (permissive SELinux did not help on the previous board).
- Other errors (missing library, `1002`) → fix plumbing first (paths, skel, `pd_session: unsigned`), re-run; these are not architecture signals.

### 26.7 Install and run

```powershell
./gradlew :app:assemblePlanADebug        # or assemblePlanBDebug
& $ADB install -r -t android/app/build/outputs/apk/planA/debug/app-planA-debug.apk
& $ADB shell am start -n com.megathon.incidentvoice/.MainActivity
& $ADB logcat -s IV:* IVNative:* DEBUG:* AndroidRuntime:*
```
PowerShell note: wrap native calls with `$ErrorActionPreference = "Continue"` (stderr from `am start`/`javac` is not an error).

### 26.8 One-command restore

`scripts/restore_board.ps1`: inventory → fix clock → verify `C:\iv_models` hashes → push runtime/models → install APK → S1 → S4 → launch app. Must be kept working from H8 onward; it is what saves the demo if the board is wiped or swapped.

---

## 27. Fine-tuning the LLM (stretch, gated — D-49, D-64)

### 27.1 Goal

Improve extraction/patch accuracy on our task (Hinglish normalisation, category fields, omit-absent discipline) **and** shorten the production prompt (a fine-tuned model does not need few-shots → ~400 fewer prefill tokens per call). Ship only if it wins on device (§27.7).

### 27.2 Data generation (`ml/data_gen/`, P3, H1–H4)

Cloud LLMs (Claude/GPT) are used **only at build time to create training data on the laptop** — never at runtime. This does not conflict with "strictly no cloud APIs" (judged on the running system).

1. **Scenario sampler** (`scenarios.py`) draws, per sample: primary category (uniform over 10, `OTHER` 3 %), secondary (20 %), severity, language (EN 45 % / Roman Hinglish 45 % / Hindi Devanagari 10 %), completeness (complete 35 % / 1–2 mandatory missing 45 % / ≥3 missing 20 %), speaking style (calm, panicked, rambling, terse, self-correcting), time expression type (absolute, relative, day-part, none), location (registry 70 % / unregistered 30 %), named people (40 %), equipment tags, near-miss (15 %), explicit "I don't know" (10 %), second incident (5 %).
2. **Generator** (`generate.py`): prompts the cloud LLM with the scenario + `taxonomy.json` + the 9 extraction rules, asking for `{narration, gold_tool_call}` JSON. Batching + caching; ~3 000 raw samples.
3. **Checker** (`check.py`): JSON schema (Appendix D), enum validity, category-field membership, rule 3 (no fields without evidence — uses the same `Grounding` logic as the app, ported to Python), time phrase present in narration, no `null`s. Rejects ~10–20 %.
4. **ASR-noise augmentation** (`augment.py`) on 50 % of narrations (gold unchanged): lower-case, strip punctuation, insert fillers (`umm, matlab, like, you know, haan toh`), homophone swaps (`fire→fair`, `leak→leek`, `room→rum`), 3 % word drops, number words, Devanagari↔Roman flips for Hinglish tokens.
5. **Patch samples** (`make_patch.py`): from complete samples, mask 1–2 mandatory fields → run the real `Validator` (Python port) to get the question → cloud LLM writes a natural answer (+15 % "don't know", +5 % "skip", +10 % corrections of another field) → gold `update_incident_fields`.
6. **Recommendation samples** (300): cloud LLM selects from the real SOP candidates under the rules → keeps base behaviour from drifting.
7. **Render** every sample with `ml/common/prompt_builder.py` using the **`ft` prompt variant** (no few-shots) → JSONL `{"task","prompt","completion"}` where `completion = <tool json><|eot_id|>`.
8. **Splits:** train ≈ 1 600 extract + 800 patch + 300 recommend; dev 200; **test = the 150 hand-verified eval cases (§29.1), disjoint by construction** (different authoring path, de-duplicated by MinHash against train).

### 27.3 Training (Kaggle T4/P100, P3; laptop RTX 4050 as backup)

| Hyper-parameter | Value |
|---|---|
| Base | `meta-llama/Llama-3.2-3B-Instruct` |
| Method | QLoRA: 4-bit NF4, double quant; compute dtype fp16 on T4 (bf16 on newer GPUs) |
| LoRA | r = 16, alpha = 32, dropout 0.05, targets `q_proj,k_proj,v_proj,o_proj,gate_proj,up_proj,down_proj` — **never** `embed_tokens`/`lm_head` (tied embeddings; the published encodings assume base values) |
| LR / schedule | 2e-4, cosine, warmup 3 % |
| Epochs | 2 |
| Max seq len | 1 024 (ft variant prompts ≈ 750–950 tokens) |
| Batch | micro 2 × grad-accum 8 = 16 (T4); laptop: 1 × 16 with gradient checkpointing |
| Loss | completion tokens only |
| Eval | every 100 steps: dev loss + JSON-validity rate on 50 dev prompts (greedy) |
| Checkpoint | best dev loss; time guard stops cleanly before H8 |
| Expected time | ≈ 2 700 × 2 × ~900 tokens ≈ 4.9 M tokens → 60–80 min on T4 `[inferred]` |

Laptop backup reuses `~/venvs/train` (torch 2.5.1, transformers 4.46.3, peft 0.13.2, bnb 0.44.1) and the thermal guards from the previous project (pause at 83 °C GPU, checkpoint every 20 steps, sleep disabled).

### 27.4 Merge

On the Kaggle CPU session: load base fp16/bf16, apply adapter, `merge_and_unload()`, save safetensors + tokenizer files. Checks: `tie_word_embeddings: true`, 28 layers, hidden 3072, 24/8 heads, **no** separate `lm_head.weight`, `chat_template` present.

### 27.5 Host pre-gate (before spending ~3 h on export)

Run the 150-case text eval (§29) with the merged model (fp16, GPU) and with the base model, same `ft`/`base` prompt variants respectively. Continue to export only if mean per-field F1 improves by **≥ 5 points** and JSON validity ≥ base.

### 27.6 Export of the fine-tuned model (Kaggle, P1, from ~H8)

Procedure from `EXPORT_RESEARCH.md` §2 (proven on the previous project), adapted to V79:
1. `build_ft_ckpt.py <merged> /kaggle/temp/ft_ckpt_w4a16` — fetches Qualcomm's **published w4a16 encodings** for this model and exports our merged weights to the same torch.export graph (`model_dynamic.onnx` + `model.data`, ~14.4 GB).
2. `refresh_param_scales.py /kaggle/temp/ft_ckpt_w4a16` — widens per-channel weight scales that the fine-tune pushed past the published ones (thousands widened ⇒ drift warning).
3. `qai-hub-models export llama_v3_2_3b_instruct --checkpoint /kaggle/temp/ft_ckpt_w4a16 --device "Samsung Galaxy S25 (Family)" --target-runtime genie --skip-profiling --output-dir /kaggle/temp/genie_ft --zip-assets`.
4. Download → `verify_bundle.py` → `C:\iv_models\llama_ft\` with `MANIFEST.json` `"prompt_variant": "ft"`.

Pitfalls (each cost days last time): double BOS (§13.9), sampler at temp 0.8, KV in/out encodings must be tied **only if** we generate our own encodings (we don't), rope factor 32.0, pinned torch/transformers (graph node names must match the published encodings), truncated downloads.

### 27.7 Device gate (H11–H12)

Run the text eval on device with stock (`base` variant) and fine-tuned (`ft` variant). Swap to the fine-tuned bundle **only if** mean per-field F1 ≥ stock + 5 points, JSON-valid rate ≥ stock, and p50 extraction latency ≤ stock. The switch = change `models.llama_dir` to `llama_ft` in `app_config.json` (pushed) — the prompt variant follows `MANIFEST.json`. Record the result in D-64a and in the demo slides either way.

---

## 28. Fine-tuning Whisper (stretch, P2 priority, D-50)

1. **Decide the target script first** (H6 result of §16.6 language-mode test). If `force_en` (Roman) extraction wins, train toward Roman Hinglish; if `auto_en_hi` wins, train toward Devanagari Hindi + English.
2. **Data:** public Hindi–English code-switched speech (e.g. the MUCS 2021 code-switching corpus on OpenSLR; Common Voice Hindi) `[verify licence/availability at H6]` + **team recordings** (each person records ~15 min of incident-style narrations in the hall noise, transcribed by hand; this also yields the 50 eval clips, kept disjoint).
3. **Shortcut option:** an existing Hinglish Whisper checkpoint on Hugging Face (e.g. Oriserve's Hindi→Hinglish Whisper models) `[verify size/licence]` — export instead of train, if its size ≤ small.
4. **Training (laptop RTX 4050):** `openai/whisper-small`, HF `Seq2SeqTrainer`, fp16, batch 4 × grad-accum 4, lr 1e-5, warmup 200, 2 000 steps, gradient checkpointing, SpecAugment on; ~1.5 h `[inferred]`.
5. **Export with custom weights:** `qai-hub-models export whisper_small` has no `--checkpoint` flag in 0.63. Copy `qai_hub_models/models/whisper_small/` into `ml/export/whisper_custom/`, change its Hugging Face checkpoint id to the local fine-tuned folder, and run that module's export with the same flags as §25.4 `[verify H9]`.
6. **Gate:** relative WER/CER improvement ≥ 10 % on Hinglish clips **and** English WER regression ≤ 5 %; otherwise keep stock.

---
## 29. Evaluation plan (D-58)

### 29.1 Datasets (`eval/`)

| Set | Size | Content | Built by | When |
|---|---|---|---|---|
| `eval/text_cases.jsonl` | **150** | Narration text (EN 60, Roman Hinglish 60, noisy-ASR-style 30) + gold `fill_incident_form` + for 50 of them a scripted follow-up exchange with gold patches | P3 (cloud-LLM-assisted drafting, **every case hand-checked**) | H1–H5 |
| `eval/clips/*.wav` + `eval/clips.jsonl` | **50** | Real recordings by the team in the hackathon hall (noise!): 25 EN, 25 Hinglish; gold transcript (verbatim, in the script the speaker used) + gold form | All three, 17 clips each, during breaks | H2–H9 |
| `eval/slide_example` | 1 | The PPT example — must always pass | P3 | H1 |

Coverage requirement: every category ≥ 10 text cases and ≥ 3 clips; ≥ 15 cases with secondary types; ≥ 15 with relative times; ≥ 10 with "don't know"; 5 with two incidents.

### 29.2 ASR metrics

- **WER** (English) and **CER** (Hinglish/Hindi, because spelling of Roman Hinglish is not standardised) after normalisation: lower-case, strip punctuation, collapse spaces, number words → digits, common Hinglish spelling variants unified (`hai/he`, `nahi/nahin/nai`, `kya/kia`). Computed with `jiwer`.
- Reported separately for EN and Hinglish, per language mode (`auto_en_hi` vs `force_en`).

### 29.3 Extraction and mapping metrics

| Metric | Definition |
|---|---|
| Type accuracy | `incident_type` exact match |
| Secondary F1 | set F1 over `secondary_types` |
| Per-field F1 | For each field present in gold or prediction: enums exact; strings fuzzy (token-set ratio ≥ 0.8 after lower-casing); lists set-F1 with fuzzy element match; `time` → compare **resolved** instants (±15 min, or same day-part for approximate); `severity` exact and ±1 level reported separately. Micro-averaged over all fields; also macro per field. |
| **Mapping accuracy** | Fraction of predicted values placed under the correct field key (a correct value under the wrong key counts as a mapping error, e.g. a witness name in `persons`) — judging criterion 5. |
| Hallucination rate | Predicted fields absent from gold (precision side), split by field kind |
| Mandatory completeness | After extraction alone, fraction of mandatory fields satisfied; after the scripted follow-up exchange, fraction completed within 3 rounds |
| JSON validity | Fraction of outputs parsed without repair; with repair |
| End-to-end (clips) | Same metrics computed on the form produced from audio (ASR + LLM) |

### 29.4 Latency metrics

Per stage p50/p90 (from `turns` timings and `GenStats`): VAD tail, ASR, extraction TTFT, extraction total, patch total, recommendation total, end-of-speech → form complete. Measured with the board at < 80 °C NPU at start (cold-start rule, §33.2).

### 29.5 Harness

- **Host** (`eval/run_host.py`): runs text cases through the Python prompt builder and either HF transformers (laptop GPU, 4-bit — for fast prompt iteration; *not* identical numerics to the device) or the board via Plan-B-style `iv_npud` over adb (identical numerics). Writes `eval/results/<run>/predictions.jsonl`.
- **Device** (P1): Settings → Debug → "Run eval": reads `files/eval/` (pushed), runs cases through the real orchestrator in headless mode (TTS off, scripted answers), writes `results.jsonl`; `eval/pull_and_score.ps1` pulls and scores.
- **Scorer** (`eval/score.py`): reads predictions + gold + `taxonomy.json`, prints the metric table, writes `report.md` (used directly in the pitch slides).

### 29.6 Targets (for the demo slide; honest reporting either way)

| Metric | Target (stock + prompting) | Stretch (fine-tuned) |
|---|---|---|
| Type accuracy (text) | ≥ 90 % | ≥ 95 % |
| Per-field F1 (text) | ≥ 0.80 | ≥ 0.88 |
| Mapping accuracy | ≥ 0.95 | ≥ 0.97 |
| JSON valid without repair | ≥ 97 % | ≥ 99 % |
| WER EN clips | ≤ 15 % | — |
| CER Hinglish clips | ≤ 25 % | ≤ 18 % (FT Whisper) |
| End-of-speech → form (20 s narration) p50 | ≤ 8 s | ≤ 6 s |

---

## 30. Testing strategy

| Level | What | Tooling | Owner |
|---|---|---|---|
| Unit (JVM, `:core`) | Validator (§14.6), SeverityRules, TimeResolver (≥ 40 cases incl. Hinglish), LocationMatcher, ToolCallParser (malformed JSON corpus of 30 strings), PartialJson, Grounding, PromptBuilder golden test | JUnit, `./gradlew :core:test` (< 10 s) | P3 |
| Unit (host C++) | Mel vs reference, vocab decode (UTF-8 multibyte incl. Devanagari), JSON DTOs | CTest | P1 |
| Python parity | `prompt_builder.py` == Kotlin golden files; Python Validator port == Kotlin on 150 cases | pytest | P3 |
| Instrumented | Room + FTS5 (insert, reindex, search, filters), repository transactions | AndroidJUnit on the board | P2 |
| Engine | S0–S4 smoke tests (§26.5); `MockInferenceEngine` contract test runs the same suite as JNI/HTTP engines | scripts + JUnit | P1 |
| Orchestrator | Scripted conversations with `MockInferenceEngine` (happy path, 3-round limit, UNKNOWN, skip, correction, parse failure → repair → manual, engine error → retry, emergency) | coroutines-test + Turbine | P2/P3 |
| End-to-end | Demo scenes 1–4 on the board, airplane mode | manual checklist | all |
| Soak | 20 consecutive reports; watch memory (`dumpsys meminfo`), NPU temp, no crashes | manual | P1 |

**Definition of done for any P0 feature:** unit tests (if `:core`), works with Mock engine, works on the board with the real engine, no crash in a 5-report run, timings logged.

---

## 31. Error handling and resilience

### 31.1 Error codes

| Code | Meaning | User-facing message | Recovery |
|---|---|---|---|
| IV-E101 | Mic permission denied | "Microphone permission is needed to report by voice." | Open settings / type instead |
| IV-E102 | VAD model load failed | (silent) | Energy VAD fallback |
| IV-E103 | AudioRecord failure | "Microphone is busy or unavailable." | Retry / type |
| IV-E201 | ASR returned empty for speech | "I couldn't make that out. Please try again." | Re-record |
| IV-E202 | ASR engine error | "Speech engine busy — retrying…" | Retry policy §31.3 |
| IV-E301 | LLM engine error / empty output | "On-device AI engine busy — retrying…" | Retry policy; never shown as content error |
| IV-E302 | LLM timeout (> 20 s extract, > 12 s recommend) | — | Abort; extract → retry once; recommend → template fallback |
| IV-E401 | Tool call unparseable after repair | "I couldn't fill everything — please check the form." | MANUAL_FILL |
| IV-E402 | Wrong tool name | as E401 | as E401 |
| IV-E501 | DB write failed | "Couldn't save — retrying." | Retry ×3, then keep in memory and warn |
| IV-E601 | NPU device create failed (14001) | "On-device AI engine unavailable." | Plan B / engine status screen |
| IV-E602 | Context create failed (1002/5005) | "On-device AI engine starting… (retry n/3)" | 3 s / 7 s / 15 s back-off |
| IV-E603 | Model files missing/size mismatch | "Model files missing: <name>. Run setup." | Model status screen |
| IV-E604 | Thermal throttle | "Device is hot — responses may be slower." | Defer background work |
| IV-E605 | Plan B server unreachable | "Inference service not running." | Auto-relaunch (F-27) / start script |

### 31.2 Principles

1. **Hardware failures are reported as hardware failures** (never "the model refused"). Empty output = engine error.
2. **No dead ends:** every error state offers "Type the report instead" (manual form) so a report can always be filed.
3. **Never lose data:** drafts and turns are persisted before any engine call.
4. Every error is logged with code, stage, incident id, timings, and the last 2 KB of native log.

### 31.3 Retry policy (engine)

- Device/context creation and generation errors flagged `retryable`: retry after **3 s, 7 s, 15 s** with the identical prompt (results unchanged with greedy decoding). Show "retrying (n/3)".
- After the third failure: `ERROR_ENGINE`; the orchestrator keeps the transcript and offers manual fill; a background re-init is attempted every 60 s.
- Generation aborted by priority preemption is **not** an error; it is re-queued.

---

## 32. Security and privacy

### 32.1 Data

- Everything stays on the device: DB and audio in app-private storage (`/data/data/<pkg>`), `allowBackup=false`.
- Plan A ships **without `INTERNET` permission** — the OS guarantees no network egress (D-54). Shown on the "About" screen as a trust signal.
- Exports leave the device only through an explicit user share action.
- Reporter identity is a free-text profile (no authentication in scope).

### 32.2 Model and data integrity

- `MANIFEST.json` sizes checked at startup; sha256 checked in the background.
- Prompts and taxonomy are inside the APK (not user-writable).
- LLM output is treated as untrusted input: schema-validated, enum-checked, grounded (§13.6–13.8); it can never execute anything but the registered functions.

### 32.3 Device hardening (hackathon realities)

- `ro.adb.secure` is unset on this board: anyone with a USB cable has a shell. Keep the board attended.
- Plan B with `persist.adb.tcp.port=5555` exposes an **unauthenticated shell on the network**. Keep Wi-Fi disabled (`svc wifi disable`) or on an isolated hotspot; revert the property after the event (§19.4).
- The Plan B HTTP server binds `127.0.0.1` only and checks `X-IV-Token`.

### 32.4 Safety of advice

Recommendations are selected from a curated SOP list (§12) with a visible disclaimer; the emergency banner always tells the user to call the ERT first. The LLM cannot invent medical procedures beyond one `CUSTOM` line, which is labelled as AI-suggested.

---

## 33. Performance, thermal and memory operations

### 33.1 Memory

- Both models loaded once (§7.6). Watch `MemAvailable` in the debug overlay (Plan B: `/v1/metrics`; Plan A: `ActivityManager.MemoryInfo`).
- `largeHeap=true` for the app; native allocations dominate anyway.
- Never start a second engine (Plan B supervisor kills orphans).

### 33.2 Thermal

- Thermal status: Plan A uses `PowerManager.getCurrentThermalStatus()` and `getThermalHeadroom(10)` (app-readable); Plan B reads `/sys/class/thermal/thermal_zone*/temp` for `nsphvx-*`/`nsphmx-*`.
- Defer recommendations, eval batches and warm-ups when status ≥ `THERMAL_STATUS_MODERATE` (≈ NPU > 88 °C on the previous board).
- Benchmarks/eval: wait until the NPU is < 80 °C before starting (cold-start rule), otherwise numbers drift downward.
- Board on a stand with airflow during the demo; no case.

### 33.3 Power

Board on its supplied adapter (not laptop USB power — bus-powered boards dropped off USB on the previous project). Screen stays on while plugged in (§26.3).

---

## 34. Repository structure and conventions

### 34.1 Monorepo layout (D-59)

```
megathon-qidk/
├── PRD.md                         # this document (single source of truth)
├── RA.txt, Problem Statement.pptx, problems_setup.md, qidk guide.pdf
├── qidk/                          # Qualcomm QIDK reference repo (read-only reference)
├── config/                        # SHARED CONTRACTS (§22.5) — taxonomy, sop, site registry, app_config, prompts
│   ├── taxonomy.json  sop.json  site_registry.json  app_config.json
│   ├── genie/genie_config.template.json  htp_backend_ext_config.json
│   └── prompts/ extract.system.txt extract.system.ft.txt extract.user.txt extract.fewshot.jsonl
│                patch.system.txt patch.user.txt recommend.system.txt recommend.user.txt
│                repair.system.txt repair.user.txt golden/
├── android/                       # Gradle project: settings.gradle.kts, :core, :app
│   ├── core/  app/
├── android-probe/                 # throwaway NPU probe app (§26.6)
├── native/                        # C++ core, JNI, worker (§18)
├── server-go/                     # Plan B server (§19)
├── ml/
│   ├── common/ prompt_builder.py taxonomy.py validator.py grounding.py
│   ├── data_gen/ scenarios.py generate.py check.py augment.py make_patch.py render.py
│   ├── finetune_llm/ train_qlora.py merge.py kaggle_train.ipynb
│   ├── finetune_whisper/ train.py
│   ├── export/ kaggle_llama_export.ipynb build_ft_ckpt.py refresh_param_scales.py whisper_custom/
│   └── scripts/ verify_bundle.py make_manifest.py export_whisper_assets.py count_tokens.py extract_epcontext.py
├── eval/ text_cases.jsonl clips.jsonl clips/ run_host.py score.py pull_and_score.ps1 results/
├── scripts/ env.ps1 board_inventory.ps1 fix_clock.ps1 push_models.ps1 smoke_llm.ps1 smoke_asr.ps1
│            planb_start.ps1 restore_board.ps1 cleanup_board.ps1
├── docs/ board_inventory.txt probe_result.md demo_script.md slides/
└── _board_runtime_backup/         # QAIRT 2.47 V79 runtime pulled from the board (git-ignored)
```

### 34.2 Branches (names final when the team decides, D-59)

Suggested: `main` (demo-ready only), `integration` (merged hourly), feature branches `p1/native-*`, `p2/app-*`, `p3/core-*`, `p3/data-*`. Merge to `integration` at every milestone (§35.2); `main` only from a passing S6 smoke test.

### 34.3 Conventions

- Kotlin: official style, `ktlint` optional; coroutines everywhere, no blocking on Main.
- C++17, `clang-format` (Google style), no exceptions across JNI boundaries (return JSON errors).
- Go: `gofmt`, standard library HTTP.
- Python: 3.11, `ruff`.
- JSON files: 2-space indent, keys `snake_case`, enums `UPPER_SNAKE`.
- Commit messages: `area: summary` (e.g. `core: validator pairing rule`).
- Large files (`*.bin`, `*.onnx`, `*.wav` > 1 MB, models) are **never** committed; `.gitignore` enforces it (`eval/clips/*.wav` are stored on the integration laptop and shared via drive).

---

## 35. The 15-hour execution plan

### 35.1 Hour 0 — contracts first (all three, 30 min, before splitting)

Freeze, commit to `integration`:
1. `config/taxonomy.json` v1 (Appendix A as-is), `config/sop.json` v1 (Appendix B), `config/app_config.json` (§22.5).
2. Tool schemas (Appendix D) and DTOs (`EngineInfo`, `AsrResult`, `GenStats`).
3. `InferenceEngine` interface (§22.1) + `MockInferenceEngine` canned outputs for demo scene 1.
Then everyone works against these. Changing a contract requires a 2-minute sync with the other two.

### 35.2 Timeline

| Hour | P1 — ML/NPU | P2 — Android | P3 — Brain | Milestone |
|---|---|---|---|---|
| 0–1 | Kick off E2 (Kaggle Llama export) and E3/E4 (Whisper) ; download QAIRT 2.50 (E1); board inventory, clock fix | Install Android Studio/NDK; create Gradle project (`:core`, `:app`), Compose skeleton, nav, theme | Contracts (§35.1) — P3 leads; start `:core` models + taxonomy loader | **M0 (H1):** contracts frozen, exports running |
| 1–3 | Push 2.50 runtime; S0; read `HfWhisperApp`; dump Whisper IO; start `native/` (qnn_loader, mel + host tests) ; build `android-probe` | AudioCapture + Silero VAD + WAV writer; Conversation screen with Mock engine; TTS speaker (eSpeak) | Validator + SeverityRules + TimeResolver + LocationMatcher + tests; prompts v0; start eval text cases; data-gen scripts | |
| 3–5 | **Probe (S5)**; Whisper decode loop on board as shell (S4); Llama bundle arrives → verify → S1–S3 (BOS, greedy, speed) | Room + FTS5 + repository; Review screen; Dashboard list + search | ToolCallParser + PartialJson + Grounding + PromptBuilder (+ Python twin, golden tests); prompt iteration on laptop GPU (HF 4-bit) with eval cases; launch Kaggle QLoRA (H4) | **M1 (H5):** speech → transcript → tool-call JSON on the board via CLI; probe decided (Plan A/B) |
| 5–8 | `libiv_native.so` JNI (Genie + Whisper + scheduler) → `JniInferenceEngine`; or Plan B worker + Go server if probe failed | Orchestrator state machine wired to Mock; follow-up loop UI; emergency banner; settings/profile | Orchestrator logic with P2 (merge, rounds, UNKNOWN/skip); SOP candidate filter + recommend prompt; record eval clips; host eval numbers | **M2 (H8):** full conversation on the board with the real engine (one category end-to-end) |
| 8–11 | Warm-up, abort/priority, timings → overlay; prefix cache (P1); FT export on Kaggle if host pre-gate passed | Dashboard filters + stats charts + detail; review per-field edit; PDF/JSON export (P1) | All categories tested end-to-end; fix prompt failures from eval; Python validator parity; device eval runner content | **M3 (H11):** all P0 features on the board |
| 11–12 | Device eval: stock vs FT (gate D-64); thermal checks | Polish: loading states, error states, empty states | Score eval → `report.md`; slide numbers | **Gate (H12):** FT decision; feature freeze |
| 12–13.5 | Soak test (20 reports); `restore_board.ps1` verified | Bug fixes only | Demo script rehearsal ×2; slides | **M4 (H13.5):** release candidate on `main` |
| 13.5–15 | Buffer / fixes | Buffer / fixes | Final rehearsal; backup video recording of the demo | **Demo** |

### 35.3 Cut lines (if behind schedule, cut in this order)

1. F-27 laptop-free Plan B launch (keep laptop-attached start).
2. F-26 fine-tuned Llama; F-30 fine-tuned Whisper.
3. F-23 prefix cache.
4. F-21 PDF/JSON export (keep DB).
5. F-20 per-field mic (keep typed edits).
6. Dashboard charts (keep counts as text).
7. Hinglish follow-up templates (English only).
**Never cut:** on-NPU ASR + LLM, validator loop with TTS, review + submit, DB + search, emergency banner.

### 35.4 Communication

- Stand-up every 2 h (5 min): milestone status, blockers, contract changes.
- One shared `docs/status.md` with checkboxes per F-ID.
- Integration builds on the board laptop every milestone; others never install on the board directly without telling P1 (one NPU session rule applies to people too).

---

## 36. Risk register

| # | Risk | Likelihood | Impact | Mitigation / fallback |
|---|---|---|---|---|
| R1 | Llama export fails or takes > 5 h | Medium | High | Kaggle primary; laptop L2; 1B L3; CPU llama.cpp L4 (§25.2). Start at H0. |
| R2 | App sandbox refused NPU (14001) | Low–Medium | High | Plan B fully specified; same C++ core; decided at H3–H5. |
| R3 | Genie + our QNN Whisper conflict in one process | Medium | Medium | Whisper in child process (`iv_npud --asr-only`) (§18.5). |
| R4 | QAIRT 2.50 download blocked | Low | High | 2.45-compile + board 2.47 libs + Plan B-CLI (§25.6). |
| R5 | Hinglish ASR quality poor | Medium | Medium | Editable transcript; language-mode test; LLM normalisation; FT Whisper stretch; per-field typed edits. |
| R6 | Extraction JSON invalid/hallucinated | Medium | Medium | Strict prompt rules, greedy, parser leniency, repair call, grounding, manual fill path. |
| R7 | Latency > 8 s | Medium | Medium | O1–O15; shorter output; prefix cache; FT prompt variant; 1B fallback. |
| R8 | Noise in hall breaks VAD | Medium | Medium | Hysteresis thresholds, manual stop, configurable silence, push-to-stop. |
| R9 | eSpeak voice quality hurts demo | High | Low | Show text bubbles prominently; F-31 engine swap if time. |
| R10 | Board clock wrong → wrong times | High (observed) | Medium | `fix_clock.ps1`, app warning bar. |
| R11 | Laptop disk/thermal problems | Medium | Medium | VHDX compaction done before H0 (Appendix H); heavy jobs on Kaggle. |
| R12 | Truncated/corrupt downloads | Medium | High | `verify_bundle.py` header + sha256 checks. |
| R13 | Double BOS / sampler plumbing bugs mistaken for model quality | High (history) | High | §13.9 checklist at H4 before any prompt tuning. |
| R14 | Two adb versions conflict | Medium | Low | Single adb path in `env.ps1`. |
| R15 | Room/bundled SQLite FTS5 build issues | Low | Low | Room `@Fts4` fallback (§21.4). |
| R16 | Wrong `soc_model`/arch config | Low | High | Never hand-write; use AI Hub-generated `htp_backend_ext_config.json`; verify script. |
| R17 | Venue network slow (Kaggle/AI Hub uploads) | Medium | High | Start exports first thing; mobile hotspot backup; Kaggle avoids laptop uplink for the 14 GB Llama upload. |
| R18 | Thermal throttling mid-demo | Low | Medium | Cool board before demo; defer background work. |
| R19 | Fine-tune makes things worse | Medium | Low | Strict gate; stock is always the shipped default. |
| R20 | Team integration conflicts | Medium | Medium | Frozen contracts, Mock engine, hourly integration branch. |

---

## 37. Optional features (designed, not committed)

Per instructions, no clarification questions were asked about these; designs below are suggestions that fit the architecture without changing P0 contracts.

### O-1 Speak out the recommended steps

`TtsSpeaker.speak()` reads the top 3 recommended actions after "Here are the recommended next steps", with a "Stop" button; skipped if `severity = CRITICAL` (the banner already speaks). Zero model work.

### O-2 Conversational query over the local DB (RA.txt open question)

- New LLM task `query` with tool `search_incidents{text?, types?, severity?, status?, from?, to?, location?, limit?}` (Appendix D style). Llama converts "show me all fires in Block B last week" into filters; the repository executes them (FTS5 + SQL); a second short call (`summarise_results`) or a template turns rows into an answer, optionally spoken (O-1).
- **Vector database:** not required for this. Add only for "find incidents similar to this one": `sqlite-vec` extension in the same DB file + a small CPU embedding model (e.g. a MiniLM/BGE-small class model via ONNX Runtime) over `title + description`. Hybrid ranking = FTS bm25 + cosine.
- Same NPU rules apply (one session, priorities).

### O-3 Cloud data and conversational management with alerts

- Outbox pattern: the reserved `sync_state` column (`LOCAL_ONLY → PENDING → SYNCED`), a WorkManager job that syncs when a network is available and the user enabled sync; server side out of scope.
- Alerts: on `CRITICAL` submit, push to a configured channel (SMS/webhook) when online.
- Requires the `INTERNET` permission → a separate build flavour so the offline guarantee of Plan A is preserved.

---

## 38. Demo script (≈ 6 minutes)

**Setup:** board on stand with fan, 12 V adapter, both speakers connected, wired headset mic plugged into the 3.5 mm jack, HDMI to the projector (`S5702-7/8` ON before power-on, §6.4), airplane mode / Wi-Fi off, cooled < 80 °C, app open on Home showing "NPU ready", debug overlay ON, a couple of prior incidents in the dashboard. Backup: recorded video.

| Scene | Script | What judges see | Criteria hit |
|---|---|---|---|
| 1. The slide example (EN) | "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area." → Q1 "Was anyone hurt, and how serious is the situation right now?" → "No one is hurt, it's minor." → Q2 "Where is the water coming from, and is it still leaking?" → "A pipe under the sink burst, it's stopped now." | Transcript, streaming form, tool-call JSON in overlay, two spoken follow-ups, recommendations, submit, ref no | 1, 3, 4, 5, function calling |
| 2. Hinglish + relative time + injury | "Arre, Block B ke store room mein ek ladka seedhi se gir gaya, uske haath mein chot lagi hai, kareeb aadha ghanta pehle. First aid de diya hai." | English fields from Hinglish; resolved time "≈ 30 min ago"; MEDICAL; asks about ambulance/condition in Hinglish | 2, 4 |
| 3. Emergency | "Fire! There's smoke coming from the electrical panel in Bay 3, aag fail rahi hai!" | Instant amber → red banner, Call ERT, shortened questions, FIRE + ELECTRICAL | 2, safety |
| 4. Dashboard | Search "leak", filter HIGH+, open detail, change status to Acknowledged, share PDF | Recency list, FTS search, stats charts | RA dashboard |
| 5. Numbers | Slide: WER/CER, type accuracy, per-field F1, mapping accuracy, p50 latency, tok/s on HTP V79 | Honest metrics from `eval/report.md` | 3, 4, 5 |

---
## Appendix A — `config/taxonomy.json` (complete, v1)

Copy this file verbatim into `config/taxonomy.json` at H0. It is the canonical definition of §10 and §11.

```json
{
  "version": 1,
  "groups_order": ["type", "where_when", "impact", "victim", "hazard", "response"],
  "common_fields": [
    {"key": "incident_type", "type": "enum_category", "mandatory": true, "group": "type", "priority": 1, "label": "Incident type",
     "q": {"en": "What kind of incident is this — for example a fire, a leak, an injury, or an equipment problem?",
           "hinglish": "Yeh kis type ka incident hai — jaise aag, leak, chot, ya machine ki problem?"}},
    {"key": "secondary_types", "type": "enum_category_list", "max_items": 2, "mandatory": false, "label": "Also involves"},
    {"key": "title", "type": "string", "max_len": 80, "mandatory": false, "system": true, "label": "Title"},
    {"key": "description", "type": "string", "mandatory": true, "group": "type", "priority": 2, "label": "Description",
     "q": {"en": "Could you briefly describe what happened?", "hinglish": "Thoda batayiye, exactly kya hua?"}},
    {"key": "location", "type": "string", "mandatory": true, "group": "where_when", "priority": 3, "label": "Location",
     "q": {"en": "Where exactly did this happen?", "hinglish": "Yeh exactly kahan hua?"}},
    {"key": "occurred_at", "type": "time_expression", "llm_key": "time", "mandatory": true, "group": "where_when", "priority": 4, "label": "Date & time",
     "q": {"en": "Around what time did it happen?", "hinglish": "Yeh lagbhag kitne baje hua?"}},
    {"key": "severity", "type": "enum", "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"], "mandatory": true, "group": "impact", "priority": 5, "label": "Severity",
     "q": {"en": "How serious is it right now — minor, significant, or is anyone in danger?",
           "hinglish": "Abhi situation kitni serious hai — chhoti problem, badi problem, ya kisi ki jaan ko khatra hai?"}},
    {"key": "injuries", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": true, "group": "impact", "priority": 6, "label": "Anyone injured?",
     "q": {"en": "Was anyone hurt?", "hinglish": "Kya kisi ko chot lagi?"}},
    {"key": "injured_count", "type": "int", "min": 0, "mandatory": false, "group": "impact", "label": "Number injured"},
    {"key": "persons", "type": "person_list", "mandatory": false, "label": "Persons involved",
     "item": {"name": "string", "role": ["AFFECTED", "INVOLVED", "RESPONDER", "REPORTER", "OTHER"], "injured": "bool", "injury": "string"}},
    {"key": "equipment", "type": "string_list", "mandatory": false, "group": "hazard", "priority": 7, "label": "Equipment / asset",
     "q": {"en": "Which machine or equipment is involved?", "hinglish": "Kaunsi machine ya equipment involved hai?"}},
    {"key": "actions_taken", "type": "string_list", "mandatory": false, "label": "Immediate actions taken"},
    {"key": "witnesses", "type": "witness_list", "mandatory": false, "label": "Witnesses", "item": {"name": "string", "contact": "string"}},
    {"key": "near_miss", "type": "bool", "mandatory": false, "label": "Near miss"},
    {"key": "hazard_ongoing", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "impact", "label": "Hazard still present"},
    {"key": "other_incidents", "type": "string_list", "mandatory": false, "label": "Other incidents mentioned"},
    {"key": "lang", "type": "enum", "enum": ["en", "hinglish", "hi"], "mandatory": false, "system": true, "label": "Language"}
  ],
  "pair_templates": [
    {"fields": ["location", "occurred_at"], "en": "Where exactly did this happen, and around what time?", "hinglish": "Yeh kahan hua, aur lagbhag kitne baje?"},
    {"fields": ["injuries", "severity"], "en": "Was anyone hurt, and how serious is the situation right now?", "hinglish": "Kya kisi ko chot lagi, aur abhi situation kitni serious hai?"},
    {"fields": ["incident_type", "description"], "en": "Could you tell me what happened?", "hinglish": "Batayiye, kya hua?"},
    {"fields": ["fire_status", "evacuation_status"], "en": "Is the fire still burning, and has the area been evacuated?", "hinglish": "Kya aag abhi bhi lagi hai, aur kya area khaali karwa diya gaya hai?"},
    {"fields": ["first_aid_given", "ambulance_called"], "en": "Has first aid been given, and has an ambulance been called?", "hinglish": "Kya first aid diya gaya, aur kya ambulance bulayi gayi?"},
    {"fields": ["leak_source", "leak_status"], "en": "Where is the water coming from, and is it still leaking?", "hinglish": "Paani kahan se aa raha hai, aur kya abhi bhi leak ho raha hai?"},
    {"fields": ["substance", "release_status"], "en": "What gas or chemical is it, and is it still leaking?", "hinglish": "Kaunsi gas ya chemical hai, aur kya abhi bhi leak ho raha hai?"},
    {"fields": ["vehicles_involved", "collision_type"], "en": "Which vehicles were involved, and what did they hit?", "hinglish": "Kaunsi gaadiyan involved thi, aur takkar kisse hui?"}
  ],
  "categories": [
    {"code": "FIRE", "label": "Fire / Smoke / Explosion",
     "definition": "flames, smoke, burning smell, explosion or blast",
     "search_cues": ["fire", "smoke", "flames", "burning", "explosion", "blast", "aag", "dhuan", "dhamaka", "jal"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "fire_status", "type": "enum", "enum": ["ACTIVE", "CONTAINED", "EXTINGUISHED", "SMOKE_ONLY", "UNKNOWN"], "mandatory": true, "group": "hazard", "priority": 10, "label": "Fire status",
        "q": {"en": "Is the fire still burning, or is it under control?", "hinglish": "Aag abhi bhi lagi hai, ya control mein hai?"}},
       {"key": "evacuation_status", "type": "enum", "enum": ["COMPLETED", "IN_PROGRESS", "NOT_STARTED", "NOT_REQUIRED", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 11, "label": "Evacuation",
        "q": {"en": "Has the area been evacuated?", "hinglish": "Kya area khaali karwa diya gaya hai?"}},
       {"key": "fire_brigade_called", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 12, "label": "Fire brigade called",
        "q": {"en": "Has the fire brigade been called?", "hinglish": "Kya fire brigade ko call kiya gaya?"}},
       {"key": "ignition_source", "type": "string", "mandatory": false, "group": "hazard", "label": "Ignition source"},
       {"key": "extinguisher_used", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "response", "label": "Extinguisher used"},
       {"key": "explosion_occurred", "type": "bool", "mandatory": false, "group": "hazard", "label": "Explosion occurred"}
     ]},
    {"code": "MEDICAL", "label": "Medical Emergency / Injury",
     "definition": "any injury or illness to a person",
     "search_cues": ["injury", "injured", "hurt", "fell", "bleeding", "fainted", "chot", "gir", "khoon", "behosh", "ghayal"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "patient_condition", "type": "enum", "enum": ["CONSCIOUS_STABLE", "CONSCIOUS_DISTRESSED", "UNCONSCIOUS", "NOT_BREATHING", "DECEASED", "UNKNOWN"], "mandatory": true, "group": "victim", "priority": 10, "label": "Patient condition",
        "q": {"en": "Is the injured person conscious and breathing?", "hinglish": "Kya ghayal vyakti hosh mein hai aur saans le raha hai?"}},
       {"key": "first_aid_given", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 11, "label": "First aid given",
        "q": {"en": "Has first aid been given?", "hinglish": "Kya first aid diya gaya?"}},
       {"key": "ambulance_called", "type": "enum", "enum": ["YES", "NO", "NOT_NEEDED", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 12, "label": "Ambulance called",
        "q": {"en": "Has an ambulance been called?", "hinglish": "Kya ambulance bulayi gayi?"}},
       {"key": "injury_nature", "type": "string", "mandatory": false, "group": "victim", "label": "Nature of injury"},
       {"key": "body_part", "type": "string", "mandatory": false, "group": "victim", "label": "Body part"},
       {"key": "injury_cause", "type": "string", "mandatory": false, "group": "hazard", "label": "Cause of injury"}
     ]},
    {"code": "WATER", "label": "Water Leak / Flooding",
     "definition": "water leaking or flooding indoors: pipes, roof, AC, tanks",
     "search_cues": ["water", "leak", "flood", "pipe", "paani", "tapak", "bhar"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "leak_source", "type": "string", "mandatory": true, "group": "hazard", "priority": 10, "label": "Source of leak",
        "q": {"en": "Where is the water coming from?", "hinglish": "Paani kahan se aa raha hai?"}},
       {"key": "leak_status", "type": "enum", "enum": ["ONGOING", "CONTROLLED", "STOPPED", "UNKNOWN"], "mandatory": true, "group": "hazard", "priority": 11, "label": "Leak status",
        "q": {"en": "Is the water still leaking, or has it stopped?", "hinglish": "Kya paani abhi bhi leak ho raha hai, ya ruk gaya?"}},
       {"key": "water_supply_isolated", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "response", "label": "Water supply isolated"},
       {"key": "affected_extent", "type": "string", "mandatory": false, "group": "hazard", "label": "Affected area"},
       {"key": "electrical_risk", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "hazard", "label": "Water near electricals"}
     ]},
    {"code": "ELECTRICAL", "label": "Electrical Hazard / Outage",
     "definition": "electric shock, sparking, short circuit, exposed wiring, overheating, power failure",
     "search_cues": ["electrical", "shock", "spark", "short circuit", "wire", "power cut", "current", "bijli", "taar"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "electrical_hazard", "type": "enum", "enum": ["SHOCK", "SPARKING", "SHORT_CIRCUIT", "EXPOSED_WIRING", "OVERHEATING", "POWER_OUTAGE", "OTHER"], "mandatory": true, "group": "hazard", "priority": 10, "label": "Electrical hazard",
        "q": {"en": "What exactly is the electrical problem — sparking, a shock, exposed wires, or a power cut?", "hinglish": "Electrical problem kya hai — spark, current laga, khula taar, ya light chali gayi?"}},
       {"key": "power_isolated", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 11, "label": "Power isolated",
        "q": {"en": "Has the power supply been switched off?", "hinglish": "Kya power supply band kar di gayi hai?"}},
       {"key": "outage_scope", "type": "string", "mandatory": false, "group": "hazard", "label": "Outage scope"},
       {"key": "affected_circuit", "type": "string", "mandatory": false, "group": "hazard", "label": "Affected circuit / panel"}
     ]},
    {"code": "HAZMAT", "label": "Gas / Chemical / Hazardous Substance",
     "definition": "gas leak, chemical spill or fumes with exposure risk to people",
     "search_cues": ["gas", "chemical", "fumes", "acid", "ammonia", "spill", "smell", "badbu"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "substance", "type": "string", "mandatory": true, "group": "hazard", "priority": 10, "label": "Substance",
        "q": {"en": "What gas or chemical is it?", "hinglish": "Kaunsi gas ya chemical hai?"}},
       {"key": "release_status", "type": "enum", "enum": ["ONGOING", "CONTAINED", "STOPPED", "UNKNOWN"], "mandatory": true, "group": "hazard", "priority": 11, "label": "Release status",
        "q": {"en": "Is it still leaking, or has it been contained?", "hinglish": "Kya abhi bhi leak ho raha hai, ya control ho gaya?"}},
       {"key": "area_evacuated", "type": "enum", "enum": ["YES", "NO", "PARTIAL", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 12, "label": "Area evacuated",
        "q": {"en": "Have people been moved away from the area?", "hinglish": "Kya logon ko us area se hata diya gaya?"}},
       {"key": "quantity_estimate", "type": "string", "mandatory": false, "group": "hazard", "label": "Quantity"},
       {"key": "exposure_symptoms", "type": "string", "mandatory": false, "group": "victim", "label": "Exposure symptoms"},
       {"key": "ppe_used", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "response", "label": "PPE used"}
     ]},
    {"code": "EQUIPMENT", "label": "Equipment / Machinery Failure",
     "definition": "breakdown or malfunction of machines, tools, HVAC, pumps or other equipment",
     "search_cues": ["machine", "equipment", "breakdown", "jammed", "pump", "motor", "kharab", "band"],
     "extra_mandatory_common": ["equipment"],
     "fields": [
       {"key": "failure_description", "type": "string", "mandatory": true, "group": "hazard", "priority": 10, "label": "What failed",
        "q": {"en": "What exactly went wrong with the equipment?", "hinglish": "Machine mein exactly kya kharabi hui?"}},
       {"key": "operational_status", "type": "enum", "enum": ["STOPPED", "DEGRADED", "RUNNING", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 11, "label": "Operational status",
        "q": {"en": "Is the equipment stopped, or still running?", "hinglish": "Machine band hai, ya abhi bhi chal rahi hai?"}},
       {"key": "lockout_tagout", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "response", "label": "Lockout/tagout applied"},
       {"key": "production_impact", "type": "string", "mandatory": false, "group": "hazard", "label": "Production impact"}
     ]},
    {"code": "SECURITY", "label": "Security / Theft / Intrusion / Violence",
     "definition": "theft, break-in, trespass, unauthorised access, vandalism, assault, threats, suspicious activity",
     "search_cues": ["theft", "stolen", "intruder", "break-in", "fight", "assault", "chori", "ladai", "dhamki"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "security_event", "type": "enum", "enum": ["THEFT", "INTRUSION", "UNAUTHORIZED_ACCESS", "VANDALISM", "ASSAULT", "THREAT", "SUSPICIOUS_ACTIVITY", "OTHER"], "mandatory": true, "group": "hazard", "priority": 10, "label": "Security event",
        "q": {"en": "What happened exactly — theft, a break-in, unauthorised entry, or a fight?", "hinglish": "Exactly kya hua — chori, ghuspaith, bina permission entry, ya ladai?"}},
       {"key": "security_informed", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 11, "label": "Security informed",
        "q": {"en": "Has the security team been informed?", "hinglish": "Kya security team ko bataya gaya?"}},
       {"key": "suspect_description", "type": "string", "mandatory": false, "group": "hazard", "label": "Suspect description"},
       {"key": "items_affected", "type": "string_list", "mandatory": false, "group": "hazard", "label": "Items affected"},
       {"key": "police_informed", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "response", "label": "Police informed"}
     ]},
    {"code": "STRUCTURAL", "label": "Structural / Facility Damage",
     "definition": "damage to building or facilities, slip/trip hazards, broken glass, collapsed racks, stuck lifts",
     "search_cues": ["ceiling", "crack", "collapse", "glass", "lift", "elevator", "wet floor", "chhat", "darar", "sheesha"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "damage_type", "type": "string", "mandatory": true, "group": "hazard", "priority": 10, "label": "Type of damage",
        "q": {"en": "What kind of damage is it?", "hinglish": "Kis tarah ka damage hua hai?"}},
       {"key": "area_cordoned", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 11, "label": "Area cordoned off",
        "q": {"en": "Has the area been cordoned off?", "hinglish": "Kya area ko ghera ya band kar diya gaya hai?"}},
       {"key": "people_trapped", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "victim", "label": "People trapped"}
     ]},
    {"code": "ENVIRONMENTAL", "label": "Environmental Release / Pollution",
     "definition": "release of oil, chemicals or waste into drains, soil, water or air; abnormal emissions",
     "search_cues": ["drain", "oil", "pollution", "effluent", "emission", "waste", "naala", "tel", "kachra"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "pollutant", "type": "string", "mandatory": true, "group": "hazard", "priority": 10, "label": "Pollutant",
        "q": {"en": "What was released?", "hinglish": "Kya cheez release hui ya phaili?"}},
       {"key": "release_medium", "type": "enum", "enum": ["AIR", "DRAIN", "WATER_BODY", "SOIL", "MULTIPLE", "UNKNOWN"], "mandatory": true, "group": "hazard", "priority": 11, "label": "Released into",
        "q": {"en": "Did it go into a drain, the soil, water, or the air?", "hinglish": "Kya yeh naale, mitti, paani ya hawa mein gaya?"}},
       {"key": "containment_status", "type": "enum", "enum": ["ONGOING", "CONTAINED", "STOPPED", "UNKNOWN"], "mandatory": true, "group": "response", "priority": 12, "label": "Containment",
        "q": {"en": "Is it still spreading, or has it been contained?", "hinglish": "Kya yeh abhi bhi phail raha hai, ya rok diya gaya?"}},
       {"key": "env_quantity", "type": "string", "mandatory": false, "group": "hazard", "label": "Quantity"}
     ]},
    {"code": "VEHICLE", "label": "Vehicle / Traffic / Forklift",
     "definition": "collisions or incidents involving forklifts, trucks, cars or bikes on premises",
     "search_cues": ["vehicle", "forklift", "truck", "car", "collision", "accident", "gaadi", "takkar"],
     "extra_mandatory_common": [],
     "fields": [
       {"key": "vehicles_involved", "type": "string_list", "mandatory": true, "group": "hazard", "priority": 10, "label": "Vehicles involved",
        "q": {"en": "Which vehicles were involved?", "hinglish": "Kaunsi gaadiyan involved thi?"}},
       {"key": "collision_type", "type": "enum", "enum": ["VEHICLE_VEHICLE", "VEHICLE_PERSON", "VEHICLE_OBJECT", "ROLLOVER", "OTHER", "UNKNOWN"], "mandatory": true, "group": "hazard", "priority": 11, "label": "Collision type",
        "q": {"en": "Did the vehicle hit another vehicle, a person, or an object?", "hinglish": "Gaadi ne dusri gaadi, kisi insaan, ya kisi cheez ko takkar maari?"}},
       {"key": "vehicle_ids", "type": "string_list", "mandatory": false, "group": "hazard", "label": "Vehicle IDs / plates"},
       {"key": "driver_name", "type": "string", "mandatory": false, "group": "victim", "label": "Driver"},
       {"key": "traffic_blocked", "type": "enum", "enum": ["YES", "NO", "UNKNOWN"], "mandatory": false, "group": "response", "label": "Traffic blocked"}
     ]},
    {"code": "OTHER", "label": "Other", "definition": "anything not covered by the other categories",
     "search_cues": ["other"], "extra_mandatory_common": [], "fields": []}
  ],
  "enum_synonyms": {
    "YES": ["yes", "y", "true", "haan", "han", "ha", "ji", "done", "called", "informed"],
    "NO": ["no", "n", "false", "nahi", "nahin", "nai", "not yet", "abhi nahi"],
    "UNKNOWN": ["unknown", "not sure", "dont know", "don't know", "pata nahi", "maloom nahi", "unsure"],
    "EXTINGUISHED": ["put out", "out", "bujh gayi", "extinguished"],
    "CONTAINED": ["under control", "controlled", "control mein", "contained"],
    "ACTIVE": ["burning", "spreading", "still on", "lagi hai", "active"],
    "STOPPED": ["stopped", "ruk gaya", "band ho gaya", "fixed"],
    "ONGOING": ["still leaking", "ongoing", "continuing", "abhi bhi", "chal raha"],
    "COMPLETED": ["evacuated", "everyone out", "khaali", "completed"],
    "CRITICAL": ["critical", "life threatening", "emergency", "jaan ko khatra"],
    "HIGH": ["high", "serious", "major", "badi"],
    "MEDIUM": ["medium", "moderate"],
    "LOW": ["low", "minor", "small", "chhoti", "not serious"]
  },
  "severity_rubric": {
    "CRITICAL": "Life-threatening or uncontrolled: unconscious or not breathing person, fire spreading, ongoing gas or chemical release with people exposed, people trapped, structural collapse with people at risk, electrocution.",
    "HIGH": "Serious injury needing medical treatment beyond first aid; hazard active but being controlled; major damage; production stopped; assault or ongoing intrusion.",
    "MEDIUM": "Minor injury (first aid only); hazard controlled; limited damage; equipment degraded; theft without harm.",
    "LOW": "No injury and minimal damage; near miss; hazard already resolved; cosmetic issues."
  },
  "emergency": {
    "keywords": [
      "fire spreading", "spreading fire", "aag (lag|fail|phail)", "आग", "explosion", "blast", "dhamaka", "धमाका",
      "unconscious", "behosh", "बेहोश", "not breathing", "saans nahi", "सांस नहीं", "collapsed", "fainted",
      "gas leak", "gas ki (smell|badbu)", "गैस", "electrocut", "current laga", "shock laga",
      "trapped", "phas(a|e|i) (gaya|gaye|gayi|hue)", "heavy bleeding", "bahut khoon", "खून"
    ],
    "exclude": ["fire drill", "mock drill", "fire extinguisher (check|inspection|refill)", "drill"],
    "category_hint": {"aag|fire|explosion|blast|dhamaka|आग|धमाका": "FIRE", "behosh|unconscious|not breathing|saans|bleeding|khoon|बेहोश|खून": "MEDICAL",
                      "gas|गैस": "HAZMAT", "electrocut|current laga|shock laga": "ELECTRICAL", "trapped|phas": "STRUCTURAL"}
  },
  "reprompts": {
    "no_speech": {"en": "Sorry, I didn't catch that. {question}", "hinglish": "Maaf kijiye, sunaai nahi diya. {question}"},
    "no_fill": {"en": "I still need: {fields}. {question}", "hinglish": "Mujhe abhi bhi chahiye: {fields}. {question}"},
    "round_limit": {"en": "Thanks. Please check the highlighted fields on the screen.", "hinglish": "Dhanyavaad. Screen par highlighted fields check kar lijiye."},
    "complete": {"en": "Thank you. I have everything I need. Here are the recommended next steps.", "hinglish": "Dhanyavaad. Saari jaankari mil gayi. Yeh rahe agle steps."},
    "emergency": {"en": "Possible emergency. Call the emergency team now if anyone is in danger.", "hinglish": "Yeh emergency ho sakti hai. Agar kisi ko khatra hai to abhi emergency team ko call kijiye."}
  }
}
```

### A.2 Field-key uniqueness check

`ml/common/taxonomy.py --check` and a `:core` unit test assert: every key across `common_fields` and all category `fields` is unique; every `pair_templates` field exists; every mandatory field has both `q.en` and `q.hinglish` (common `equipment` included because `EQUIPMENT` makes it mandatory).

### A.3 `config/site_registry.json` (demo sample)

```json
{
  "version": 1,
  "site": "Demo Plant & Campus",
  "locations": [
    {"id": "A-G-REC", "name": "Main Reception", "building": "Block A", "floor": "G", "aliases": ["reception", "front desk", "lobby"]},
    {"id": "A-1-CR1", "name": "Conference Room 1", "building": "Block A", "floor": "1", "aliases": ["conf room 1", "CR1", "meeting room 1"]},
    {"id": "A-1-CR2", "name": "Conference Room 2", "building": "Block A", "floor": "1", "aliases": ["conf room 2", "CR2", "meeting room 2"]},
    {"id": "A-1-PAN", "name": "Pantry", "building": "Block A", "floor": "1", "aliases": ["kitchen", "cafeteria pantry"]},
    {"id": "A-2-SRV", "name": "Server Room", "building": "Block A", "floor": "2", "aliases": ["data centre", "IT room"]},
    {"id": "A-2-WS", "name": "Open Workspace", "building": "Block A", "floor": "2", "aliases": ["office floor", "desks area"]},
    {"id": "A-R-TER", "name": "Terrace", "building": "Block A", "floor": "Roof", "aliases": ["roof", "chhat"]},
    {"id": "B-G-STR", "name": "Store Room", "building": "Block B", "floor": "G", "aliases": ["store", "storeroom", "godown"]},
    {"id": "B-G-B1", "name": "Bay 1", "building": "Block B", "floor": "G", "aliases": ["production bay 1"]},
    {"id": "B-G-B2", "name": "Bay 2", "building": "Block B", "floor": "G", "aliases": ["production bay 2"]},
    {"id": "B-G-B3", "name": "Bay 3", "building": "Block B", "floor": "G", "aliases": ["production bay 3"]},
    {"id": "B-G-B4", "name": "Bay 4", "building": "Block B", "floor": "G", "aliases": ["production bay 4", "racking area"]},
    {"id": "B-G-L1", "name": "Assembly Line 1", "building": "Block B", "floor": "G", "aliases": ["line 1"]},
    {"id": "B-G-L2", "name": "Assembly Line 2", "building": "Block B", "floor": "G", "aliases": ["line 2"]},
    {"id": "B-1-DB3", "name": "Electrical Panel DB-3", "building": "Block B", "floor": "1", "aliases": ["DB-3", "DB 3", "distribution board 3", "panel 3"]},
    {"id": "B-1-CMP", "name": "Compressor Room", "building": "Block B", "floor": "1", "aliases": ["compressor house"]},
    {"id": "B-1-LAB", "name": "QC Lab", "building": "Block B", "floor": "1", "aliases": ["quality lab", "lab"]},
    {"id": "C-G-CHM", "name": "Chemical Store", "building": "Block C", "floor": "G", "aliases": ["chemical godown", "hazmat store"]},
    {"id": "C-G-BLR", "name": "Boiler House", "building": "Block C", "floor": "G", "aliases": ["boiler room"]},
    {"id": "C-G-ETP", "name": "Effluent Treatment Plant", "building": "Block C", "floor": "G", "aliases": ["ETP", "effluent plant"]},
    {"id": "C-G-DG", "name": "DG Set Room", "building": "Block C", "floor": "G", "aliases": ["generator room", "diesel generator"]},
    {"id": "Y-LD", "name": "Loading Dock", "building": "Yard", "floor": "G", "aliases": ["dock", "loading bay"]},
    {"id": "Y-PK", "name": "Parking Area", "building": "Yard", "floor": "G", "aliases": ["parking", "car park"]},
    {"id": "Y-G1", "name": "Main Gate", "building": "Yard", "floor": "G", "aliases": ["gate 1", "security gate"]},
    {"id": "Y-WT", "name": "Water Tank Area", "building": "Yard", "floor": "G", "aliases": ["overhead tank", "tanki"]},
    {"id": "A-G-STA", "name": "Staircase A", "building": "Block A", "floor": "All", "aliases": ["stairs", "seedhi", "staircase"]},
    {"id": "A-G-LFT", "name": "Lift A", "building": "Block A", "floor": "All", "aliases": ["lift", "elevator"]},
    {"id": "A-G-WR", "name": "Washroom Ground Floor", "building": "Block A", "floor": "G", "aliases": ["toilet", "restroom", "washroom"]},
    {"id": "A-G-CAF", "name": "Cafeteria", "building": "Block A", "floor": "G", "aliases": ["canteen", "food court"]},
    {"id": "B-G-WH", "name": "Warehouse", "building": "Block B", "floor": "G", "aliases": ["finished goods store", "FG store"]}
  ]
}
```

---

## Appendix B — `config/sop.json` (complete, v1)

Advisory steps drawn from general EHS practice. `done_keywords` are used to drop steps already reflected in `actions_taken` (§12.2).

```json
{
  "version": 1,
  "disclaimer": "Recommendations follow site SOPs. In an emergency, follow your site's emergency procedures and call the emergency team first.",
  "categories": {
    "FIRE": [
      {"id": "FIRE-01", "text": "Raise the fire alarm and call the emergency team / fire brigade.", "immediate": true, "done_keywords": ["alarm", "fire brigade called", "called fire"]},
      {"id": "FIRE-02", "text": "Evacuate everyone from the area by the nearest safe exit to the assembly point.", "immediate": true, "when": {"evacuation_status": ["NOT_STARTED", "IN_PROGRESS", "UNKNOWN"]}, "done_keywords": ["evacuated"]},
      {"id": "FIRE-03", "text": "Do not use lifts; use the stairs.", "immediate": true},
      {"id": "FIRE-04", "text": "Use the correct extinguisher only if the fire is small and you are trained (CO2 for electrical fires).", "when": {"fire_status": ["ACTIVE", "SMOKE_ONLY", "UNKNOWN"]}, "done_keywords": ["extinguisher"]},
      {"id": "FIRE-05", "text": "Isolate electrical power to the affected area if it is safe to reach the switch.", "done_keywords": ["power off", "isolated power"]},
      {"id": "FIRE-06", "text": "Take a head count at the assembly point and report missing persons to the fire team.", "immediate": false},
      {"id": "FIRE-07", "text": "Keep the area closed until the fire team declares it safe; check for hidden embers and smoke damage.", "when": {"fire_status": ["CONTAINED", "EXTINGUISHED"]}},
      {"id": "FIRE-08", "text": "Get the electrical installation inspected before re-energising.", "when": {"ignition_source": ["*"]}},
      {"id": "FIRE-09", "text": "Refill or replace any used extinguishers.", "when": {"extinguisher_used": ["YES"]}}
    ],
    "MEDICAL": [
      {"id": "MED-01", "text": "Call the emergency team / ambulance and the site first-aider.", "immediate": true, "when": {"ambulance_called": ["NO", "UNKNOWN"]}, "done_keywords": ["ambulance called", "called ambulance"]},
      {"id": "MED-02", "text": "If the person is not breathing, start CPR if trained and use the nearest AED.", "immediate": true, "when": {"patient_condition": ["NOT_BREATHING", "UNCONSCIOUS"]}},
      {"id": "MED-03", "text": "Do not move the injured person unless they are in immediate danger.", "immediate": true},
      {"id": "MED-04", "text": "Give first aid within your training: control bleeding with firm pressure, cool burns with running water.", "when": {"first_aid_given": ["NO", "UNKNOWN"]}, "done_keywords": ["first aid"]},
      {"id": "MED-05", "text": "Stay with the person and keep them warm and calm until help arrives."},
      {"id": "MED-06", "text": "Make the cause of the injury safe (e.g. dry the wet floor, guard the machine) to protect others."},
      {"id": "MED-07", "text": "Inform the person's supervisor and HR, and record treatment given."},
      {"id": "MED-08", "text": "Arrange a medical check-up even for minor injuries before the person returns to work."}
    ],
    "WATER": [
      {"id": "WATER-01", "text": "Isolate the water supply valve feeding the leak.", "immediate": true, "when": {"leak_status": ["ONGOING", "UNKNOWN"]}, "done_keywords": ["valve", "supply off", "isolated water"]},
      {"id": "WATER-02", "text": "Switch off electrical equipment and sockets in the wet area if it is safe to do so.", "immediate": true, "when": {"electrical_risk": ["YES", "UNKNOWN"]}},
      {"id": "WATER-03", "text": "Cordon off the wet area and place wet-floor signs to prevent slips.", "done_keywords": ["isolated the area", "cordoned", "sign"]},
      {"id": "WATER-04", "text": "Move documents, IT equipment and stock away from the water."},
      {"id": "WATER-05", "text": "Mop up or pump out standing water and dry the area.", "when": {"leak_status": ["STOPPED", "CONTROLLED"]}},
      {"id": "WATER-06", "text": "Ask maintenance to repair the source of the leak and inspect nearby pipes or roof sections."},
      {"id": "WATER-07", "text": "Check ceilings, walls and electrical fittings for water damage before reuse."},
      {"id": "WATER-08", "text": "Watch for mould or odour over the next days and log any follow-up repairs."}
    ],
    "ELECTRICAL": [
      {"id": "ELEC-01", "text": "Do not touch the person or equipment if there may be live current; switch off power at the source.", "immediate": true, "when": {"power_isolated": ["NO", "UNKNOWN"]}, "done_keywords": ["power off", "switched off", "isolated"]},
      {"id": "ELEC-02", "text": "Keep everyone away from sparking or exposed wiring; cordon the area.", "immediate": true, "when": {"electrical_hazard": ["SPARKING", "SHORT_CIRCUIT", "EXPOSED_WIRING", "OVERHEATING"]}},
      {"id": "ELEC-03", "text": "Anyone who received a shock must get a medical check, even if they feel fine.", "when": {"electrical_hazard": ["SHOCK"]}},
      {"id": "ELEC-04", "text": "Call a licensed electrician to inspect and repair before re-energising."},
      {"id": "ELEC-05", "text": "Apply lockout/tagout on the affected circuit until repairs are done."},
      {"id": "ELEC-06", "text": "For a power outage, check critical loads (servers, cold storage, safety systems) and switch to backup power if available.", "when": {"electrical_hazard": ["POWER_OUTAGE"]}},
      {"id": "ELEC-07", "text": "Check for burning smell or heat at panels; keep a CO2 extinguisher nearby."}
    ],
    "HAZMAT": [
      {"id": "HAZ-01", "text": "Evacuate people from the area, moving upwind of the release.", "immediate": true, "when": {"area_evacuated": ["NO", "PARTIAL", "UNKNOWN"]}, "done_keywords": ["evacuated", "moved people"]},
      {"id": "HAZ-02", "text": "Eliminate ignition sources: no switches, phones or flames near a gas leak.", "immediate": true},
      {"id": "HAZ-03", "text": "Shut the gas or chemical supply valve if it can be done safely.", "immediate": true, "when": {"release_status": ["ONGOING", "UNKNOWN"]}},
      {"id": "HAZ-04", "text": "Ventilate the area by opening doors and windows if safe."},
      {"id": "HAZ-05", "text": "Only trained responders with the right PPE should contain the spill using spill kits.", "done_keywords": ["spill kit", "contained"]},
      {"id": "HAZ-06", "text": "Check the substance's safety data sheet (SDS) for exposure first aid and handling."},
      {"id": "HAZ-07", "text": "Send anyone with symptoms (coughing, irritation, dizziness) for medical attention.", "when": {"exposure_symptoms": ["*"]}},
      {"id": "HAZ-08", "text": "Dispose of contaminated material as hazardous waste and record the quantity released."}
    ],
    "EQUIPMENT": [
      {"id": "EQP-01", "text": "Stop the equipment using the emergency stop and keep people clear.", "immediate": true, "when": {"operational_status": ["RUNNING", "DEGRADED", "UNKNOWN"]}, "done_keywords": ["stopped", "switched off"]},
      {"id": "EQP-02", "text": "Apply lockout/tagout before anyone inspects or repairs the equipment.", "when": {"lockout_tagout": ["NO", "UNKNOWN"]}, "done_keywords": ["lockout", "tagout", "loto"]},
      {"id": "EQP-03", "text": "Inform maintenance with the equipment ID and a description of the fault."},
      {"id": "EQP-04", "text": "Check for secondary hazards: leaks, overheating, smoke or loose parts."},
      {"id": "EQP-05", "text": "Notify the production supervisor of the impact and switch to backup equipment if available."},
      {"id": "EQP-06", "text": "Do not restart until the equipment is repaired and tested."},
      {"id": "EQP-07", "text": "Record the failure for the maintenance history and root-cause analysis."}
    ],
    "SECURITY": [
      {"id": "SEC-01", "text": "Do not confront the suspect; move to a safe place and inform security.", "immediate": true, "when": {"security_informed": ["NO", "UNKNOWN"]}, "done_keywords": ["security informed", "told security"]},
      {"id": "SEC-02", "text": "If anyone is hurt or threatened, call the emergency team / police.", "immediate": true, "when": {"security_event": ["ASSAULT", "THREAT"]}},
      {"id": "SEC-03", "text": "Preserve the scene: do not touch or move items the suspect handled."},
      {"id": "SEC-04", "text": "Secure CCTV footage and access-control logs for the time of the incident."},
      {"id": "SEC-05", "text": "Note a description of the suspect and the direction they went."},
      {"id": "SEC-06", "text": "List stolen or damaged items with asset tags and estimated value.", "when": {"security_event": ["THEFT", "VANDALISM"]}},
      {"id": "SEC-07", "text": "Inform the police and file a complaint for theft or assault.", "when": {"police_informed": ["NO", "UNKNOWN"]}},
      {"id": "SEC-08", "text": "Review and fix the access weakness (door, gate, badge) that allowed the incident."}
    ],
    "STRUCTURAL": [
      {"id": "STR-01", "text": "Keep people away and cordon off the damaged area.", "immediate": true, "when": {"area_cordoned": ["NO", "UNKNOWN"]}, "done_keywords": ["cordoned", "isolated the area", "blocked"]},
      {"id": "STR-02", "text": "If anyone is trapped (e.g. in a lift), call the emergency team and do not attempt an unsafe rescue.", "immediate": true, "when": {"people_trapped": ["YES", "UNKNOWN"]}},
      {"id": "STR-03", "text": "Place warning signs for slip or trip hazards and clean or fix them promptly."},
      {"id": "STR-04", "text": "Clear broken glass or debris with gloves and proper tools."},
      {"id": "STR-05", "text": "Ask facilities/civil engineering to assess structural safety before reuse."},
      {"id": "STR-06", "text": "For lift faults, take the lift out of service and call the lift maintenance contractor."},
      {"id": "STR-07", "text": "Photograph the damage for the record and insurance."}
    ],
    "ENVIRONMENTAL": [
      {"id": "ENV-01", "text": "Stop the source of the release if it is safe to do so.", "immediate": true, "when": {"containment_status": ["ONGOING", "UNKNOWN"]}},
      {"id": "ENV-02", "text": "Block nearby drains with drain covers or absorbent booms to stop spread.", "immediate": true, "when": {"release_medium": ["DRAIN", "WATER_BODY", "MULTIPLE", "UNKNOWN"]}},
      {"id": "ENV-03", "text": "Contain and absorb the spill with a spill kit; keep it away from soil and water.", "done_keywords": ["spill kit", "contained"]},
      {"id": "ENV-04", "text": "Inform the environment/EHS officer; check if regulatory reporting is required."},
      {"id": "ENV-05", "text": "Collect contaminated material and dispose of it as hazardous waste."},
      {"id": "ENV-06", "text": "Estimate and record the quantity released and the affected area."},
      {"id": "ENV-07", "text": "Find and fix the cause (leaking container, faulty valve, wrong disposal)."}
    ],
    "VEHICLE": [
      {"id": "VEH-01", "text": "Stop all vehicle movement in the area and switch off the involved vehicles.", "immediate": true},
      {"id": "VEH-02", "text": "If a person was hit, call the emergency team and give first aid; do not move them unless in danger.", "immediate": true, "when": {"collision_type": ["VEHICLE_PERSON"]}},
      {"id": "VEH-03", "text": "Cordon off the area and redirect traffic.", "when": {"traffic_blocked": ["YES", "UNKNOWN"]}},
      {"id": "VEH-04", "text": "Check for fuel or battery leaks and fire risk."},
      {"id": "VEH-05", "text": "Record vehicle IDs, driver details and take photos before moving vehicles."},
      {"id": "VEH-06", "text": "Inspect damaged racks or structures before use (forklift impacts)."},
      {"id": "VEH-07", "text": "Remove the vehicle from service until inspected; review driver training and route markings."}
    ],
    "OTHER": [
      {"id": "OTH-01", "text": "Make the area safe and keep people away from any hazard.", "immediate": true},
      {"id": "OTH-02", "text": "Inform your supervisor and the EHS team."}
    ],
    "GENERAL": [
      {"id": "GEN-01", "text": "Inform your supervisor and the EHS team about this incident."},
      {"id": "GEN-02", "text": "Preserve evidence: photos, CCTV, and do not disturb the scene unnecessarily."},
      {"id": "GEN-03", "text": "Collect witness statements while details are fresh."},
      {"id": "GEN-04", "text": "Schedule a root-cause review and track corrective actions to closure."}
    ]
  }
}
```
`when` semantics: the key's current value must be in the list; `"*"` = any non-empty value; a missing field satisfies the condition.

---

## Appendix C — Prompts (complete)

All templates use `{{placeholder}}` substitution only (no logic). `PromptBuilder` (Kotlin) and `prompt_builder.py` render them identically.

### C.1 `extract.system.txt` (base variant)

```
You are IncidentVoice, an incident-report extraction engine for an industrial plant and office campus.
You receive the transcript of a worker describing an incident by voice. The speaker may use English, Hinglish (Hindi-English mix in Roman letters) or Hindi. The transcript may contain speech-recognition errors and filler words.

Your job: call the function fill_incident_form exactly once with the information in the transcript.

RULES
1. Output exactly one JSON object {"name": "fill_incident_form", "parameters": {...}} and nothing else.
2. Write every value in clear, simple English, even if the speaker used Hindi or Hinglish. Keep names of people, rooms, buildings, machines and asset tags exactly as spoken, in Roman letters.
3. Include a field ONLY if the transcript states it or it is directly implied. Never guess. Omit fields you do not know. Do not output null, empty strings or "unknown".
4. Use UNKNOWN for an enum field only when the speaker explicitly says they do not know.
5. "time": the time phrase as spoken, translated to English (for example "around 10 AM", "about an hour ago", "last night at 11"). Do not calculate dates.
6. "incident_type": the one best category code. "secondary_types": up to 2 more codes, only with clear evidence.
7. "severity": choose with the severity rubric, only if the transcript gives enough information.
8. "details": only fields that belong to the chosen categories, using the listed values.
9. If the speaker describes another, separate incident, summarise it in "other_incidents" and do not mix its details in. "lang": "en", "hinglish" or "hi" for the language the speaker used.

CATEGORIES
{{category_list}}
Tie-break: if a person was injured, MEDICAL is primary unless the cause is an ongoing fire, gas/chemical release or electrical hazard (then that hazard is primary and MEDICAL secondary). FIRE beats ELECTRICAL. HAZMAT beats ENVIRONMENTAL when people are exposed.

SEVERITY RUBRIC
{{severity_rubric}}

CATEGORY DETAIL FIELDS (key(allowed values) or key:type)
{{field_catalogue}}

FUNCTION
{{tool_schema_fill_incident_form}}

Respond with ONLY the JSON function call.
```
Rendered placeholders:
- `{{category_list}}` → one line per category: `FIRE: flames, smoke, burning smell, explosion or blast` …
- `{{severity_rubric}}` → `CRITICAL: …` lines from `severity_rubric`.
- `{{field_catalogue}}` → e.g. `FIRE: fire_status(ACTIVE|CONTAINED|EXTINGUISHED|SMOKE_ONLY|UNKNOWN), evacuation_status(COMPLETED|IN_PROGRESS|NOT_STARTED|NOT_REQUIRED|UNKNOWN), fire_brigade_called(YES|NO|UNKNOWN), ignition_source:text, extinguisher_used(YES|NO|UNKNOWN), explosion_occurred:true/false`; `EQUIPMENT` line is prefixed with "(also fill equipment)".
- `{{tool_schema_fill_incident_form}}` → compact JSON of Appendix D.1.

`extract.system.ft.txt` (fine-tuned variant) = the same text **without** the few-shot pairs (the system text itself stays identical so the fine-tune learns from the production instructions).

### C.2 `extract.fewshot.jsonl` (base variant; 2 pairs)

```json
{"user": "Transcript:\n<<<\nThere was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.\n>>>", "assistant": "{\"name\":\"fill_incident_form\",\"parameters\":{\"lang\":\"en\",\"incident_type\":\"WATER\",\"title\":\"Water leak near Conference Room 2\",\"description\":\"A water leak occurred near Conference Room 2. Maintenance has isolated the area.\",\"location\":\"near Conference Room 2\",\"time\":\"around 10 AM\",\"actions_taken\":[\"Maintenance isolated the area\"]}}"}
{"user": "Transcript:\n<<<\nhaan toh bay 4 mein forklift ne ek rack ko thok diya aadha ghanta pehle, driver ramesh tha, kisi ko chot nahi lagi but rack thoda tedha ho gaya hai, humne area band kar diya\n>>>", "assistant": "{\"name\":\"fill_incident_form\",\"parameters\":{\"lang\":\"hinglish\",\"incident_type\":\"VEHICLE\",\"secondary_types\":[\"STRUCTURAL\"],\"title\":\"Forklift hit a rack in Bay 4\",\"description\":\"A forklift driven by Ramesh hit a rack in Bay 4 and the rack is slightly bent. No one was injured. The area has been closed off.\",\"location\":\"Bay 4\",\"time\":\"about half an hour ago\",\"injuries\":\"NO\",\"severity\":\"MEDIUM\",\"persons\":[{\"name\":\"Ramesh\",\"role\":\"INVOLVED\",\"injured\":false}],\"actions_taken\":[\"Area closed off\"],\"details\":{\"vehicles_involved\":[\"forklift\"],\"collision_type\":\"VEHICLE_OBJECT\",\"driver_name\":\"Ramesh\",\"damage_type\":\"rack bent by forklift impact\",\"area_cordoned\":\"YES\"}}}"}
```

### C.3 `extract.user.txt`

```
Transcript:
<<<
{{transcript}}
>>>
```

### C.4 `patch.system.txt`

```
You are IncidentVoice. You update an incident form using the worker's answer to a follow-up question.
Call the function update_incident_fields exactly once.

RULES
1. Output exactly one JSON object {"name": "update_incident_fields", "parameters": {...}} and nothing else.
2. "set": the asked fields that the answer provides, plus any other field the worker clearly adds or corrects. Use the same keys and allowed values as the form. Write values in clear English.
3. "unknown": asked fields for which the worker says they do not know (for example "pata nahi", "not sure", "no idea").
4. "skipped": true only if the worker asks to skip or answer later (for example "skip", "baad mein", "chhodo").
5. Never guess. Do not repeat fields that did not change.
6. "time": the time phrase as spoken, in English. "lang": "en", "hinglish" or "hi".

ALLOWED VALUES FOR THE ASKED FIELDS
{{asked_field_specs}}

FUNCTION
{{tool_schema_update_incident_fields}}

Respond with ONLY the JSON function call.
```

### C.5 `patch.user.txt`

```
Current form: {{form_compact_json}}
Question asked: "{{question_text}}"
Asked fields: {{asked_fields}}
Answer transcript:
<<<
{{answer}}
>>>
```
`{{form_compact_json}}` contains only satisfied fields (keys + values, no meta) to keep prefill short (O9).

Example completion:
```json
{"name":"update_incident_fields","parameters":{"lang":"en","set":{"injuries":"NO","severity":"LOW"},"unknown":[],"skipped":false}}
```

### C.6 `recommend.system.txt`

```
You are IncidentVoice, a workplace safety assistant. Select and adapt the most relevant follow-up actions for this incident from the SOP candidate list.
Call the function propose_actions exactly once.

RULES
1. Output exactly one JSON object {"name": "propose_actions", "parameters": {...}} and nothing else.
2. At most 6 actions, most urgent first.
3. Every action must use an "sop" id from the candidate list. Adapt the text to this incident (mention the location, equipment or substance) in at most 20 words. Keep the meaning of the SOP step.
4. Do not include actions that the form says were already taken.
5. You may add at most one extra action with "sop": "CUSTOM" only if it is clearly necessary and not covered by the list.
6. Never contradict an SOP step. Never give medical procedures beyond the SOP text.

FUNCTION
{{tool_schema_propose_actions}}

Respond with ONLY the JSON function call.
```

### C.7 `recommend.user.txt`

```
Incident: {{form_compact_json}}
Already done: {{actions_taken_list}}
SOP candidates:
{{sop_candidates}}
```
`{{sop_candidates}}` = lines `WATER-01: Isolate the water supply valve feeding the leak.`

### C.8 `repair.system.txt` / `repair.user.txt`

```
You fix broken JSON. Rewrite the text below as one valid JSON object for the function {{tool_name}} with the same content. Do not add information. Output only the JSON.
```
```
Function schema: {{tool_schema}}
Broken output:
<<<
{{broken}}
>>>
```

### C.9 Warm-up prompt

User message `Say OK.` (rendered with the chat format, no system message), greedy, max 4 tokens.

---

## Appendix D — JSON Schemas

### D.1 `fill_incident_form` (tool definition shown to the model, compact form)

```json
{"name":"fill_incident_form","description":"Fill the incident report form from the transcript. Omit unknown fields.",
 "parameters":{"type":"object","properties":{
  "lang":{"type":"string","enum":["en","hinglish","hi"]},
  "incident_type":{"type":"string","enum":["FIRE","MEDICAL","WATER","ELECTRICAL","HAZMAT","EQUIPMENT","SECURITY","STRUCTURAL","ENVIRONMENTAL","VEHICLE","OTHER"]},
  "secondary_types":{"type":"array","items":{"type":"string"},"maxItems":2},
  "title":{"type":"string"},
  "description":{"type":"string"},
  "severity":{"type":"string","enum":["LOW","MEDIUM","HIGH","CRITICAL"]},
  "location":{"type":"string"},
  "time":{"type":"string","description":"time phrase as spoken, in English"},
  "injuries":{"type":"string","enum":["YES","NO","UNKNOWN"]},
  "injured_count":{"type":"integer"},
  "persons":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"role":{"type":"string","enum":["AFFECTED","INVOLVED","RESPONDER","REPORTER","OTHER"]},"injured":{"type":"boolean"},"injury":{"type":"string"}}}},
  "equipment":{"type":"array","items":{"type":"string"}},
  "actions_taken":{"type":"array","items":{"type":"string"}},
  "witnesses":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"contact":{"type":"string"}}}},
  "near_miss":{"type":"boolean"},
  "hazard_ongoing":{"type":"string","enum":["YES","NO","UNKNOWN"]},
  "details":{"type":"object","description":"category detail fields"},
  "other_incidents":{"type":"array","items":{"type":"string"}}},
  "required":["incident_type","description"]}}
```
The parser's full validation additionally checks `details` keys/values against `taxonomy.json`.

### D.2 `update_incident_fields`

```json
{"name":"update_incident_fields","description":"Update form fields from the answer.",
 "parameters":{"type":"object","properties":{
  "lang":{"type":"string","enum":["en","hinglish","hi"]},
  "set":{"type":"object","description":"field key -> new value (common or category field keys; 'time' for the time phrase)"},
  "unknown":{"type":"array","items":{"type":"string"}},
  "skipped":{"type":"boolean"}},
  "required":["set"]}}
```

### D.3 `propose_actions`

```json
{"name":"propose_actions","description":"Recommended follow-up actions.",
 "parameters":{"type":"object","properties":{
  "actions":{"type":"array","maxItems":6,"items":{"type":"object","properties":{
     "sop":{"type":"string","description":"SOP id from the candidate list, or CUSTOM"},
     "text":{"type":"string"}},"required":["sop","text"]}}},
  "required":["actions"]}}
```

### D.4 Export schema `incident_report.v1` (abridged; full file `config/schemas/incident_report.v1.json`)

```json
{
  "schema": "incident_report.v1",
  "ref_no": "INC-20261010-0007", "id": "uuid", "status": "SUBMITTED",
  "incident_type": "WATER", "secondary_types": [], "title": "…", "description": "…",
  "severity": {"value": "LOW", "source": "USER"},
  "location": {"text": "near Conference Room 2", "registry_id": "A-1-CR2", "registry_name": "Conference Room 2, Block A, Floor 1"},
  "occurred_at": {"iso": "2026-10-10T10:00:00+05:30", "approximate": true, "expression": "around 10 AM"},
  "injuries": "NO", "injured_count": 0, "near_miss": false, "hazard_ongoing": "NO",
  "persons": [], "witnesses": [], "equipment": [], "actions_taken": ["Maintenance isolated the area"],
  "details": {"leak_source": "burst pipe under the sink", "leak_status": "STOPPED"},
  "field_meta": {"location": {"status": "FILLED", "source": "LLM_EXTRACT", "turn": 0, "grounded": true}},
  "recommendations": [{"sop": "WATER-03", "text": "…", "source": "SOP_TAILORED", "done": false}],
  "turns": [{"idx": 0, "kind": "NARRATION", "lang": "en", "text": "…", "asr_ms": 640, "llm_ms": 4210}],
  "reporter": {"name": "…", "emp_id": "…"},
  "reported_at": "2026-10-10T10:07:12+05:30", "submitted_at": "…",
  "model_versions": {"asr": "whisper_small/aihub/qairt-2.50", "llm": "llama-3.2-3b-instruct/w4a16/stock", "taxonomy": 1, "prompts": "sha1:…"}
}
```

### D.5 Engine DTOs

```jsonc
// EngineInfo
{"plan":"A","qairt":"2.50.x","soc":"SM8750P","htp_arch":"v79",
 "asr":{"model":"whisper_small","loaded":true,"load_ms":820},
 "llm":{"model":"llama_v3_2_3b_instruct","variant":"stock","prompt_variant":"base","loaded":true,"load_ms":2950,"context":4096}}
// AsrResult
{"text":"…","lang":"en","chunks":[{"start_ms":0,"end_ms":19800,"text":"…","lang":"en"}],
 "timings":{"mel_ms":38,"enc_ms":71,"dec_ms":512,"tokens":61,"total_ms":640}}
// GenStats
{"task":"EXTRACT","tokens_in":1032,"tokens_out":97,"ttft_ms":1410,"prefill_tps":731.5,"decode_tps":27.9,"wall_ms":4890,"aborted":false,"stop":"EOS"}
// EngineError
{"code":"IV-E602","message":"Could not create context from binary: err 1002","retryable":true,"native_log_tail":"…"}
```

---

## Appendix E — SQL DDL (complete; Room entities must match)

```sql
CREATE TABLE incidents (
  rowid_pk            INTEGER PRIMARY KEY AUTOINCREMENT,
  id                  TEXT NOT NULL UNIQUE,
  ref_no              TEXT UNIQUE,
  status              TEXT NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','ACKNOWLEDGED','RESOLVED','CLOSED')),
  incident_type       TEXT,
  title               TEXT,
  description         TEXT,
  severity            TEXT CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  severity_source     TEXT CHECK (severity_source IN ('LLM','RULE','USER')),
  location_text       TEXT,
  location_id         TEXT,
  occurred_at         INTEGER,
  occurred_at_approx  INTEGER NOT NULL DEFAULT 0,
  time_expression     TEXT,
  injuries            TEXT CHECK (injuries IN ('YES','NO','UNKNOWN')),
  injured_count       INTEGER,
  near_miss           INTEGER NOT NULL DEFAULT 0,
  hazard_ongoing      TEXT,
  details_json        TEXT NOT NULL DEFAULT '{}',
  field_meta_json     TEXT NOT NULL DEFAULT '{}',
  other_incidents_json TEXT NOT NULL DEFAULT '[]',
  lang                TEXT,
  reporter_name       TEXT,
  reporter_emp_id     TEXT,
  emergency_flag      INTEGER NOT NULL DEFAULT 0,
  followup_rounds     INTEGER NOT NULL DEFAULT 0,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  reported_at         INTEGER NOT NULL,
  submitted_at        INTEGER,
  model_versions_json TEXT NOT NULL DEFAULT '{}',
  sync_state          TEXT NOT NULL DEFAULT 'LOCAL_ONLY'
);
CREATE INDEX idx_incidents_status_created ON incidents(status, created_at DESC);
CREATE INDEX idx_incidents_type ON incidents(incident_type);
CREATE INDEX idx_incidents_severity ON incidents(severity);
CREATE INDEX idx_incidents_occurred ON incidents(occurred_at);

CREATE TABLE incident_secondary_types (
  incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  type        TEXT NOT NULL,
  PRIMARY KEY (incident_id, type)
);

CREATE TABLE persons (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  name        TEXT,
  role        TEXT NOT NULL CHECK (role IN ('AFFECTED','INVOLVED','RESPONDER','REPORTER','WITNESS','OTHER')),
  injured     INTEGER NOT NULL DEFAULT 0,
  injury      TEXT,
  contact     TEXT
);
CREATE INDEX idx_persons_incident ON persons(incident_id);

CREATE TABLE equipment (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  asset_tag   TEXT
);
CREATE INDEX idx_equipment_incident ON equipment(incident_id);

CREATE TABLE actions_taken (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  ord         INTEGER NOT NULL,
  text        TEXT NOT NULL
);

CREATE TABLE recommendations (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  ord         INTEGER NOT NULL,
  sop_id      TEXT NOT NULL,
  text        TEXT NOT NULL,
  source      TEXT NOT NULL CHECK (source IN ('SOP_TAILORED','SOP_TEMPLATE','CUSTOM','USER')),
  immediate   INTEGER NOT NULL DEFAULT 0,
  done        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_recs_incident ON recommendations(incident_id);

CREATE TABLE turns (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  incident_id       TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  idx               INTEGER NOT NULL,
  role              TEXT NOT NULL CHECK (role IN ('USER','ASSISTANT','SYSTEM')),
  kind              TEXT NOT NULL CHECK (kind IN ('NARRATION','FOLLOWUP_Q','FOLLOWUP_A','FIELD_EDIT','CORRECTION','NOTE')),
  asked_fields_json TEXT,
  text_raw          TEXT,
  text_edited       TEXT,
  lang              TEXT,
  audio_path        TEXT,
  audio_ms          INTEGER,
  asr_ms            INTEGER,
  llm_ms            INTEGER,
  llm_tool_json     TEXT,
  llm_stats_json    TEXT,
  created_at        INTEGER NOT NULL,
  UNIQUE (incident_id, idx)
);

CREATE TABLE audit_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  incident_id TEXT NOT NULL,
  at          INTEGER NOT NULL,
  actor       TEXT NOT NULL,
  action      TEXT NOT NULL CHECK (action IN ('CREATE','FIELD_SET','STATUS_CHANGE','SUBMIT','EXPORT','DELETE')),
  field       TEXT,
  old_value   TEXT,
  new_value   TEXT
);
CREATE INDEX idx_audit_incident ON audit_log(incident_id, at);

-- created in RoomDatabase.Callback.onCreate (Room does not model FTS5)
CREATE VIRTUAL TABLE incident_search USING fts5(
  body,
  incident_id UNINDEXED,
  tokenize = 'unicode61 remove_diacritics 2',
  prefix = '2 3'
);
```
`audit_log.incident_id` intentionally has no FK so audit entries survive deletion. `PRAGMA foreign_keys = ON` at open.

---

## Appendix F — Error → cause → fix (V79 edition)

| Symptom | Where | Likely cause | Fix |
|---|---|---|---|
| `Failed to create device: 14001` + logcat `open_device_node … Permission denied` | App | Vendor DSP service refuses app UID | Plan B (§19) |
| `14001` with no permission message | App/shell | Missing skel / wrong `ADSP_LIBRARY_PATH` (colons instead of semicolons) | Fix path; skel in `dsp/` |
| `Could not create context from binary … err 1002` | Genie/QNN | (a) arch/precision mismatch (V75 bundle on V79!), (b) transient after boot, (c) bundle switch overlap | (a) re-export for S25/V79, (b) retry 3/7/15 s, (c) never switch bundles; 0.6 s gaps |
| `Failed to free device: 14003` | Genie | follow-on of a failed create | fix the create |
| Library version mismatch / symbol errors | load | device libs older than compile version (board's 2.47 vs 2.50 bins) | push QAIRT 2.50 libs (D-63) |
| `Missing shared libraries: libcdsprpc.so` | `run-as` CLI | executables don't get the app linker namespace | not an app failure; test with an APK |
| App: `dlopen failed: library "libcdsprpc.so" not found` | App | missing `<uses-native-library>` | add to manifest (§20.9) |
| Output changes run to run | LLM | sampler not greedy | per-task sampler (§18.4) |
| Accuracy far below laptop | LLM | double BOS / prompt mismatch | §13.9 checklist |
| `Non-identical quantization parameters` | Genie load | own KV encodings untied | use published encodings (we do) |
| `qai-hub: invalid choice: 'compile'` | host | wrong tool | `qai-hub-models export` |
| `QAIRT version X is not supported` | AI Hub | pinned version retired | use listed version; device libs ≥ it |
| `must contain exactly one .onnx file` | qai-hub | HF folder to `upload_model` | `qai-hub-models export` |
| `Terminated` / exit 137 during export | WSL/Kaggle | OOM | Kaggle 30 GB; laptop needs swap + compaction |
| 401 from Hugging Face | export | Llama licence not accepted / no `HF_TOKEN` | accept licence, set token |
| `could not read ok from ADB Server` | laptop | two adb versions | kill all, use `$ADB` from `env.ps1` |
| `cannot stat 'dir\*'` | PowerShell | no glob expansion | `Get-ChildItem | ForEach-Object` |
| adb paths become `C:/Program Files/Git/data/...` | Git Bash | MSYS path conversion | `MSYS_NO_PATHCONV=1` or PowerShell |
| Times in 2024 / wrong hour | app | board clock/timezone | `fix_clock.ps1` |
| TTS silent | app | engine not visible (API 30+) | `<queries>` TTS_SERVICE in manifest; eSpeak installed |
| Whisper outputs "Thank you for watching" on noise | ASR | hallucination on non-speech | VAD speech-ratio gate + artefact filter |
| Whisper loops the same phrase | ASR | greedy repetition | repetition guard (§16.6) |

---

## Appendix G — Command cheat sheet

```powershell
# scripts/env.ps1
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"   # until Android Studio is installed: C:\Users\hp\Desktop\qidk\Code\platform-tools\adb.exe
$PKG = "com.megathon.incidentvoice"
$MODELS_A = "/sdcard/Android/data/$PKG/files/models"
$IV_B = "/data/local/tmp/iv"

& $ADB devices -l
& $ADB shell getprop ro.soc.model                                # SM8750P
& $ADB shell "df -h /data; cat /proc/meminfo | head -3"
& $ADB shell "for z in /sys/class/thermal/thermal_zone*; do echo \$(cat \$z/type) \$(cat \$z/temp); done | grep nsp"
& $ADB logcat -c; & $ADB logcat -s IV:* IVNative:* IVPROBE:* AndroidRuntime:*
& $ADB shell dumpsys meminfo $PKG | Select-String TOTAL
& $ADB shell am force-stop $PKG
```

```bash
# Git Bash: always disable path mangling for adb
export MSYS_NO_PATHCONV=1
# WSL: never use WSL's own adb; call the Windows one
alias adb='/mnt/c/Users/hp/AppData/Local/Android/Sdk/platform-tools/adb.exe'
# Llama smoke test as shell (paths per §26.5)
adb shell 'cd /data/local/tmp/iv && LD_LIBRARY_PATH=$PWD/lib ADSP_LIBRARY_PATH="$PWD/dsp;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp" ./bin/genie-t2t-run -c config/genie_config.abs.json --prompt_file test/say_ok.txt --profile'
```

---

## Appendix H — Laptop and board housekeeping (already done 2026-10-10, plus one step for you)

**Done on the board:** pulled the leftover QAIRT 2.47 V79 runtime to `_board_runtime_backup/` (genie-t2t-run, libGenie, libQnnHtp, libQnnHtpPrepare, libQnnHtpV79Stub/Skel/CalculatorStub, libQnnSystem, libc++_shared, qnn-net-run, qnn-platform-validator); deleted **all** of `/data/local/tmp` (27 GB of another project's files) → **29 GB free**. Installed apps were kept (eSpeak is our TTS engine).

**Done on the laptop:** deleted superseded WSL artifacts from the previous project (`~/gptq_out` 8.7 GB, `~/qaihm_work/ftgptq16_ckpt` 14 GB, `~/smartcity_runs` 6.6 GB, Go build cache), pip caches (WSL + Windows), npm/uv/pnpm caches, puppeteer cache, Windows `%TEMP%`; enabled sparse VHD for WSL Ubuntu and ran `fstrim`. Kept: Hugging Face Llama 3.2 3B weights, `~/venvs/{qaihm,train,kg}`, AI Hub token, `Desktop\qidk` project, Docker. C: free went from 7.6 GB to ~14 GB.

**Your step (needs an Administrator PowerShell, ~5–15 min, reclaims ~110 GB):**
```powershell
wsl --shutdown
@"
select vdisk file="C:\Users\hp\AppData\Local\wsl\{ee2e2fda-f5af-4cca-a123-4c0d0487e0d8}\ext4.vhdx"
attach vdisk readonly
compact vdisk
detach vdisk
"@ | Out-File -Encoding ascii $env:TEMP\compact.txt
diskpart /s $env:TEMP\compact.txt
```
Then check: `(Get-PSDrive C).Free/1GB` should be ~120 GB.

---

## Appendix I — References

- Problem statement: `Problem Statement.pptx` (Megathon X × Qualcomm, slides 1–5 and speaker notes).
- Field guide from the previous QIDK project: `problems_setup.md` (approaches A1–A13, error catalogue, bring-up procedure).
- Previous project's export research: `C:\Users\hp\Desktop\qidk\Code\smart-city-edge-agent\training\EXPORT_RESEARCH.md` (fine-tuned Llama → published encodings → AI Hub Genie bundle), `TRAINING.md`, `HANDBOOK.md`.
- QIDK reference repo (`qidk/`): `GenAI-Solutions/ASR-LLM-TTS` (in-app Genie + Whisper + TTS on SM8750/SM8850), `Solutions/NLPSolution3-AutomaticSpeechRecognition-Whisper`.
- qai-hub-models 0.63.0 (installed in `~/venvs/qaihm`): `models/llama_v3_2_3b_instruct/{export.py,perf.yaml,manifest.yaml}`, `models/whisper_small/{perf.yaml,manifest.yaml}`, `models/_shared/hf_whisper/app.py`.
- Qualcomm AI Hub: https://aihub.qualcomm.com/models/llama_v3_2_3b_instruct ; Workbench https://workbench.aihub.qualcomm.com
- AI Hub apps — LLM on Genie tutorial: https://github.com/quic/ai-hub-apps/tree/main/tutorials/llm_on_genie
- QIDK docs: https://qualcomm.github.io/qidk/ ; QAIRT setup: https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-2/setup.html
- Genie docs (genie-t2t-run, Genie C API): https://docs.qualcomm.com/bundle/publicresource/80-63442-100/
- Meta Llama 3.2 model card and prompt format (JSON tool calling): https://www.llama.com/docs/model-cards-and-prompt-formats/llama3_2/
- OpenAI Whisper paper: https://cdn.openai.com/papers/whisper.pdf
- Silero VAD: https://github.com/snakers4/silero-vad
- SQLite FTS5: https://www.sqlite.org/fts5.html ; Room bundled SQLite driver: https://developer.android.com/kotlin/multiplatform/sqlite

*End of PRD v1.0.*








