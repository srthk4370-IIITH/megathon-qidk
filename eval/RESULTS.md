# RESULTS

All numbers below were produced on 2026-10-11. Sample sizes are small; read differences of a few points as noise.

## 1. Extraction on the text eval set: rules baseline vs Qwen3-4B on the NPU (first prompt, model output as parsed)

**Set:** `eval/text_cases.jsonl`, N = 150 typed transcripts (75 English, 75 Hinglish), the same cases for both engines. Each transcript goes through the app's own prompt builder, tool-call parser and normalisers (`LlmEvalTool`, `android/domain/src/test`). Qwen ran on the QIDK's Hexagon NPU, one fresh Genie session per case; the rules engine ran on the laptop (text only, no NPU).

**Scoring.** Enum fields: exact match after normalisation. Location: same entry in the site registry, else containment of the place name. Time: within ±15 minutes after resolving the phrase against the case's clock. Lists of category codes: same set; free-text lists (equipment, actions taken, people): counted correct when present. Description is scored separately and is not part of precision, recall or F1. Title, language tag and "other incidents" are not scored.

| Metric | Rules baseline | Qwen3-4B (NPU) |
|---|---|---|
| N | 150 | 150 |
| Incident-type accuracy | 96.0 % (144/150) | 53.3 % (80/150) |
| Per-field precision | 0.934 | 0.537 |
| Per-field recall | 0.6 | 0.311 |
| Per-field F1 (micro) | 0.731 | 0.394 |
| Mapping accuracy (right value in the right field / expected non-empty fields) | 60.0 % (709/1182) | 31.1 % (368/1182) |
| Valid tool-call rate | 100.0 % (150/150) | 98.0 % (147/150) |
| Made-up field rate (filled although nothing was expected / fields filled) | 2.2 % (17/759) | 26.3 % (180/685) |
| Description present | 100.0 % (150/150) | 98.0 % (147/150) |
| Description: share of its words the reporter actually said (mean) | 1.0 | 0.974 |
| Description: word overlap with the reference description (mean) | 0.286 | 0.302 |

Mapping accuracy equals recall under this definition; both are shown because both were asked for.

### By language

| Language | N | Rules: type accuracy | Rules: F1 | Qwen: type accuracy | Qwen: F1 |
|---|---|---|---|---|---|
| en | 75 | 98.7 % (74/75) | 0.778 | 61.3 % (46/75) | 0.481 |
| hinglish | 75 | 93.3 % (70/75) | 0.681 | 45.3 % (34/75) | 0.306 |

### By category (incident-type accuracy)

| Category | N | Rules | Qwen3-4B |
|---|---|---|---|
| ELECTRICAL | 14 | 100.0 % (14/14) | 64.3 % (9/14) |
| ENVIRONMENTAL | 14 | 92.9 % (13/14) | 0.0 % (0/14) |
| EQUIPMENT | 14 | 100.0 % (14/14) | 35.7 % (5/14) |
| FIRE | 14 | 100.0 % (14/14) | 64.3 % (9/14) |
| HAZMAT | 14 | 85.7 % (12/14) | 50.0 % (7/14) |
| MEDICAL | 14 | 100.0 % (14/14) | 35.7 % (5/14) |
| OTHER | 10 | 80.0 % (8/10) | 70.0 % (7/10) |
| SECURITY | 14 | 100.0 % (14/14) | 14.3 % (2/14) |
| STRUCTURAL | 14 | 100.0 % (14/14) | 85.7 % (12/14) |
| VEHICLE | 14 | 100.0 % (14/14) | 85.7 % (12/14) |
| WATER | 14 | 92.9 % (13/14) | 85.7 % (12/14) |

### Dev vs held-out

`eval/dev_ids.txt` lists the 42 cases the ML branch used while writing prompts; the rest are held out for the model. The rules engine was tuned by the App branch on the **whole** set, so none of it is held out for the rules: its numbers are an upper bound, not an estimate. The Qwen prompt was written without looking at these cases.

| Split | N | Rules: type accuracy | Rules: F1 | Qwen: type accuracy | Qwen: F1 |
|---|---|---|---|---|---|
| dev | 42 | 97.6 % (41/42) | 0.792 | 57.1 % (24/42) | 0.458 |
| held-out | 108 | 95.4 % (103/108) | 0.705 | 51.9 % (56/108) | 0.369 |

### Qwen3-4B speed on these 150 extraction calls (QIDK, NPU)

| | p50 | p90 |
|---|---|---|
| Time to first token (ms) | 511 | 526 |
| Total per call (ms) | 5002 | 6062 |

Decode speed: median 22.7 tok/s (10th percentile 22.5); median output 103 tokens. Model load is not included (3.4 s per fresh session). The rules engine takes about 1 ms per case.

### What these numbers mean, and what was changed because of them

The Qwen column above is the **first** compact prompt, scored on the raw parsed output, with no keyword hint and none of the app's evidence checks. It is clearly worse than the rules engine on this set. Two cautions on the comparison: the rules were tuned on these same 150 sentences (so 96 % is an upper bound, not an estimate), and several "made-up" fields are things the speaker did say but the reference leaves out (`hazard_ongoing`, `actions_taken`).

Three real failures from that run, with the reason:

| Case | Said | Expected | Got | Reason |
|---|---|---|---|---|
| tc-008 (Hinglish) | "Dopahar sawa do baje Open Workspace mein se copper ke cable chori ho gaye…" | SECURITY, Open Workspace, 2:15 PM | WATER, "near the copper cable", "since 9 in the morning" | The model copied the category and the time from the Hinglish example inside the prompt (few-shot leakage), and did not know "chori" or "sawa do baje". |
| tc-015 (English) | "Copper cables were stolen in the Open Workspace around 10 AM. We have not told security yet." | SECURITY | OTHER | With all eleven categories packed into one line of the prompt, the model fell back to OTHER; no in-prompt example covered theft. |
| tc-002 (Hinglish) | "…forklift ne vikram singh ko takkar maar di uske pair mein chot hai…" | MEDICAL, injuries YES | VEHICLE, injuries NO | The person-injured tie-break was not applied, and "chot hai" was not read as an injury; "NO" came from the fire example in the prompt. |

Changes made in response (all in the app now): categories one per line with Hindi cue words, a Hinglish glossary for time words, examples marked as format only, the rules engine's keyword category passed as a hint the model may overrule, and deterministic evidence checks on injuries, severity, place and time (see HANDBOOK "On-device LLM").

**Re-check with the second prompt + keyword hint: a 30-case subset only** (the cases the first prompt got most wrong plus every tenth case; N = 30, picked by hand, not a random sample): incident type correct 30 of 30, against 11 of 30 for the first prompt on the same cases; median 5.3 s per call (the answers are longer), time to first token 0.6 s. The full 150-case re-run was **not** done for lack of time, so there is no like-for-like F1 for the second prompt. Because the hint comes from rules tuned on this set, type accuracy with the hint on this set says little about new sentences. Raw outputs: `eval/results_2026-10-11_qwen_outputs.jsonl` (first prompt, 150) and `eval/results_2026-10-11_qwen_v2_subset_outputs.jsonl` (second prompt, 30).

## 2. On-board app tests (Qwen3-4B on the NPU, typed input, final build)

Typed through the app's "Type instead" path with adb, so speech recognition is not part of these ten. Airplane mode was **off** during the run (the board reported `airplane_mode_on = 0`; it was not switched by the test script); the app has no INTERNET permission in any case (section 3).

| # | Input | Expected | What happened | Result |
|---|---|---|---|---|
| 1 | "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area." | Fills type, place, time, action; asks only for injuries and the leak source | Filled type, title, description, location, time, action taken. Asked "Was anyone hurt, and how serious…", then "Where is the water coming from, and is it still leaking?". Review complete after two answers | PASS |
| 2 | "There is smoke and a small fire in the panel room on the first floor. It started ten minutes ago. Everyone has been evacuated and nobody is hurt." | FIRE with place, time, no injuries | FIRE, "panel room on the first floor", "ten minutes ago", injuries NO. The word "fire" started the emergency clock, so no questions were asked; severity, fire status and evacuation are left as "Required" on the review screen | PARTIAL (a small controlled fire is treated as an emergency) |
| 3 | "Pantry ke paas paani leak ho raha hai, aadha ghanta pehle shuru hua" | Hinglish questions; time = 30 minutes before now | Both follow-up questions in Hinglish; time stored as 30 minutes earlier; description kept in the reporter's words | PASS (second question was in English on the first try; fixed and re-run) |
| 4 | "hi wassup this is person xyz" | Nothing filled; asks what happened | "…I didn't catch an incident. What happened?", form empty | PASS (it greeted the reporter as "Person"; that word is now excluded as a name, not re-run) |
| 5 | "A forklift hit the rack in Bay 4 at 10, no, 11 in the morning. Nobody was hurt." | Time 11:00 | VEHICLE, Bay 4, time 11:00, injuries NO | PASS (first try stored 10:00; fixed with the self-correction rule and re-run) |
| 6 | "there's a leak" | Asks for exactly the missing mandatory fields | Type WATER only; first question "Where exactly did this happen?", then severity and injuries, then leak source | PASS (first try the model invented location "building" and time "today"; fixed with the evidence check and re-run) |
| 7 | "fire is spreading, someone is unconscious" | Banner at once, no follow-up questions | Emergency banner and response clock at once, severity CRITICAL, injuries YES. One question was asked: "Where exactly did this happen?" (by design the place is asked once in an emergency). Type came out MEDICAL, not FIRE | PARTIAL |
| 8 | "A pipe burst in the pantry and water is on the floor. Also the lift in Block B is stuck with two people inside." | First incident filed, second offered separately | First incident filed correctly (WATER, pantry). The stuck lift was **not** captured as a second incident | FAIL |
| 9 | "umm uh hmm" (typed mumble) | Graceful, no crash | "I didn't catch an incident. What happened?" | PASS (spoken silence was not run in this pass; it is covered by a unit test) |
| 10 | A 70-word rambling account of forklift FL-3 hitting a storage rack near the loading dock around 2 pm, nobody hurt | Correct extraction | Location "loading dock", time 14:00, injuries NO, description correct; type came out MEDICAL instead of VEHICLE, which also started the response clock and skipped the questions | PARTIAL |

6 pass, 3 partial, 1 fail.

**Soak test.** 18 consecutive typed reports without restarting the app (five different reports in rotation, each with two answers; the request was 20, stopped early for time). 17 ran to the end; 1 did not start because the test script missed a tap (not an app failure). No crash and no restart: same process id throughout, no fatal exception or native crash in the log. Extraction time, first 5 vs last 5: median 4.4 s vs 4.4 s. Board temperature (thermal zone 0) between 42 and 49 °C, no upward trend.

## 3. Latency and offline proof

| Step | p50 | p90 | From |
|---|---|---|---|
| Speech recognition (Whisper small, CPU), per utterance | about 1.5 s | about 1.8 s | voice runs earlier today (10–15 s utterances; few samples) |
| LLM time to first token, extraction | 0.5–0.6 s | 0.6 s | 150 + 30 eval calls on the board |
| LLM total, extraction (in the app) | 4.4 s | 5.8 s | 17 soak reports |
| LLM total, one follow-up answer (in the app) | 1.8 s | 2.1 s | 34 answers in the soak reports |
| Decode speed | 22–23 tok/s | | all runs |
| End of speech → form filled | about 8 s | | **not measured as one number**: sum of recognition (1.5 s), the 2 s "heard this" pause and extraction (4.4 s) |

Model session reload after each call: 3.3–3.4 s, in the background.

**Permissions of the installed APK** (`aapt2 dump permissions`): RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, and the app's own DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. **There is no INTERNET permission.** WAKE_LOCK, ACCESS_NETWORK_STATE and RECEIVE_BOOT_COMPLETED come from libraries; none of them lets the app send data. The in-app proof line showed "0 B sent" throughout.

**Unit tests:** see the commit that added this file (count reported in the handbook).
