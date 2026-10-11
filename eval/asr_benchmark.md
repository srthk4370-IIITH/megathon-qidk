# ASR benchmark — Whisper tiny.en vs base.en vs small, with and without the domain hint

Run on 2026-10-11 by the App branch. Report only; nothing in the app changed.

## What was measured

- **Models** (whisper.cpp `ggml` files): `tiny.en` (75 MB), `base.en` (142 MB), and `small` multilingual quantised to q5_1 (181 MB) — the file the app ships (`ggml-small-q5_1.bin`), run with the language forced to English as the app does. Unquantised `small` was not run.
- **Decoding:** greedy (`-bs 1 -bo 1`), 4 threads, the same settings as on the board.
- **Domain hint:** the 581-character initial prompt the app writes to `models/whisper/prompt.txt` (incident topics, typical answer words, site place names). Copy in `asr_benchmark/domain_hint.txt`.
- **Accuracy** was computed on this Mac with `whisper-cli` (same model files, same decoding). **Speed** was measured on the QIDK.
- **WER** = word errors / reference words, summed over a set. Normalisation: lower-case, punctuation removed, digits split from letters; nothing else, so "11" against "eleven" counts as an error.

## Recordings (this is a small sample)

| Set | Clips | Reference words | Where the references come from |
|---|---|---|---|
| Scripted English | 15 | 428 | `eval/clips.jsonl` (`gold_transcript`), read from a script, 7–16 s each |
| Scripted Hinglish | 15 | 444 | same file; references are Hinglish in Latin script |
| Live English (board microphone) | 9 | 86 | recorded on the QIDK during the live tests, about 11 dB speech-to-noise; **references were reconstructed** from the demo sentences the speaker was reading (`asr_benchmark/live_refs.jsonl`), not transcribed independently |

39 clips in total, a handful of speakers, one room. The numbers below can separate "clearly better" from "clearly worse"; they cannot support claims to a decimal place. One wrong word in the live set moves its WER by 1.2 points.

## Accuracy

| Model | Domain hint | Scripted English WER | clips with no error | Live English WER | clips with no error | Scripted Hinglish WER |
|---|---|---|---|---|---|---|
| tiny.en | no | 10.3 % (44/428) | 3/15 | 27.9 % (24/86) | 4/9 | 111.0 % |
| tiny.en | yes | 8.2 % (35/428) | 4/15 | 10.5 % (9/86) | 5/9 | 93.9 % |
| base.en | no | 6.8 % (29/428) | 6/15 | 14.0 % (12/86) | 5/9 | 91.4 % |
| base.en | yes | 4.9 % (21/428) | 8/15 | 5.8 % (5/86) | 6/9 | 93.5 % |
| small q5_1 | no | 4.4 % (19/428) | 5/15 | 9.3 % (8/86) | 6/9 | 94.1 % |
| small q5_1 | yes | 2.8 % (12/428) | 9/15 | 2.3 % (2/86) | 8/9 | 93.7 % |

## Speed on the QIDK (SM8750P, CPU, whisper.cpp)

One 10.2 s English clip (`clip_06`), 5 runs per configuration through the ML branch's `iv_cli asr` on the board, app stopped. Time is decode time reported by the engine; model loading is not included.

| Model | Median (ms) | Min–max (ms) | Real-time factor |
|---|---|---|---|
| tiny.en | 253 | 238–258 | 0.025 |
| base.en | 411 | 405–417 | 0.040 |
| small q5_1 | 1815 | 1801–1817 | 0.178 |

With `prompt.txt` present the medians were 245 / 424 / 1811 ms: no measurable cost. Whether this particular `iv_cli` build reads the hint was not checked, so treat that line as "no slower", not as proof the hint was applied on the board.

## What the numbers say

- **small is the most accurate of the three on both English sets**, with and without the hint. On scripted English without the hint: tiny.en 10.3 %, base.en 6.8 %, small 4.4 %.
- **The hint helps every model.** It removes about a third of the errors on scripted English and more on the noisy live clips, where the errors were domain words ("water leak" heard as "virtual leap", "it's minor" as "it's my love", "forklift … Bay 4" as "fourth lift … Bedford").
- **small costs about 1.8 s for a 10 s clip on the board, against 0.25 s for tiny.en and 0.41 s for base.en.** All three are faster than real time. base.en with the hint (4.9 % / 5.8 %) is close to small without it (4.4 % / 9.3 %) at under a quarter of the time; small with the hint is still the best (2.8 % / 2.3 %).
- **Hinglish is not usable with any of these configurations**: about 91–111 % WER. The `.en` models only know English, and small with English forced writes an English rendering of the speech, which does not match a Hinglish reference word for word. This measures literal transcription only, not whether the meaning survives.

## Caveats

- The live-set references are reconstructed, and the hint was written after looking at errors on some of these same live recordings. The live "with hint" column is therefore optimistic; the scripted English set is the fairer comparison, though the hint's place names and topic words overlap with that script too.
- Accuracy was computed on the Mac, not on the board. The model files and decoding settings are the same, and the one clip timed on the board gave the same text for base.en and small as on the Mac, but that is one clip.
- An earlier on-device run by the ML branch reported 7.0 % English WER for small without a hint on these 15 scripted clips; its text normalisation differs from the one used here.
- No confidence intervals are given; with 15 and 9 clips they would be wide.

## Files

- `eval/asr_benchmark.csv` — every row of the tables.
- `eval/asr_benchmark/score.py` — scoring; `run_whisper_cli.sh` — how the hypotheses were produced; `hypotheses/*.jsonl` — every model output; `board_timing.jsonl` — raw timings; `live_refs.jsonl` — reconstructed references.
- The audio is not in the repository (the scripted clips live on the board under the ML branch's eval folder; the live recordings contain a team member's voice).
