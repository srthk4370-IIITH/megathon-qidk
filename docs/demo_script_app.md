# IncidentVoice — app demo script (≈ 6 minutes)

Works in both engine states. Home shows which one is active:
**"NPU ready"** (green) = Whisper + Llama on the board · **"On-device speech ready · LLM: mock"** (orange) = real Whisper, rule-based stand-in for the LLM (Llama bundle not pushed yet). Everything is offline in both.

## Before the audience arrives (2 minutes)

1. Board on mains power, screen brightness up, volume up (questions are spoken through eSpeak).
2. Open the app → **Settings** → set *Name / Employee ID* → **Reset for demo** (deletes old reports, loads 10 demo incidents).
3. Settings → *Show debug overlay* ON if you want timings and the raw tool call on screen.
4. Report tab: check the pill at the top (see above). If it says "Engine error", tap **Retry engine**.
5. Speak at normal volume, ~20 cm from the board. Until the speech hint is in the native library, say "water leak" and "Bay 4" clearly; a wrong word can be fixed by tapping the transcript (the countdown waits while you edit).

## Scene 1 — the slide example (voice → form → follow-ups → submit) · 90 s

1. **Report** → tap the mic → say: *"There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."*
   It stops by itself, shows the transcript, then fills the form (type, location matched to the site registry, time resolved to a date).
2. It asks out loud: *"Was anyone hurt, and how serious is the situation right now?"* → answer *"No one is hurt, it's minor."*
3. It asks: *"Where is the water coming from, and is it still leaking?"* → answer *"A pipe under the sink burst, it's stopped now."*
4. Review screen: "All required fields complete", location ✓ Conference Room 2 · Block A · Floor 1, recommended actions from the SOP library. Tap **Submit report** → reference number `INC-YYYYMMDD-NNNN`.

Point out: two fields per question, short answers ("Yes", "No", "I don't know") are understood; "I don't know" marks a field Unknown, "skip" leaves it for the review screen.

## Scene 2 — emergency · 45 s

Mic → *"There is a fire spreading in the server room and someone is unconscious."*
→ amber **Possible emergency** banner with immediate steps and **Call ERT**, spoken warning; severity is raised to CRITICAL by a rule (unconscious person), and only the essential questions are asked. Open review to show the red required fields, then **Discard**.

## Scene 3 — dashboard · 45 s

**Dashboard** → newest first, count row. Type `conf` in search (prefix match, highlighted snippet) → clear → tap **Critical** → open *Fire at DB-3 panel* → details, recommendations with tick boxes, **Similar incidents** at the bottom (vector search) → pencil icon edits a saved report and keeps its reference number.

## Scene 4 — ask the database (optional feature O-2) · 90 s

**Ask** tab. Tap the mic or a suggestion:
- *"How many critical incidents this week?"* → spoken count + the reports.
- *"What happened in Bay 4?"* → reads out the forklift report, actions taken and next steps.
- *"Was there anything about toxic fumes?"* → finds the ammonia leak **by meaning** (the words "toxic fumes" are not in that report; note the "% match").
- *"Show fires in the last 7 days"*, *"What is the latest incident?"*.

Say: questions are turned into filters plus a meaning-based search over an on-device vector index (MiniLM, 384-d, stored in the same SQLite file); a question takes ~50 ms on the board.

## Scene 5 — it is really offline · 20 s

Settings → Engine card: model folder on the device, active engine, no INTERNET permission in the app (`adb shell dumpsys package com.megathon.incidentvoice | grep INTERNET` prints nothing). Turn on aeroplane mode and repeat any scene.

## If something goes wrong

| Symptom | Do |
|---|---|
| A word is mis-heard | Tap the transcript, fix it, **Continue**. Or **Type instead**. |
| It keeps listening | Tap the red stop button; noise in the room is holding the mic open. |
| "Engine error" on Home | **Retry engine**; if it persists, **Use mock engine** (speech becomes scripted for Scene 1). |
| No voice | Settings → *Speak questions aloud* ON; volume up. Questions are always shown on screen. |
| Ask finds nothing | Reports must be **submitted** (drafts are not searched). Settings → Reset for demo. |
| App misbehaves | Force-stop and reopen: drafts and reports are kept. Never uninstall (it deletes the models). |

Developer view during the demo: `adb logcat -s IV IVNative` (states, transcripts, timings, each Ask query).
