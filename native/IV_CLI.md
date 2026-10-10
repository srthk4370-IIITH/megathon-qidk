# `iv_cli` — on-device inference CLI contract (ML branch)

One static-ish arm64 binary built from `native/` (same C++ core as the JNI library `libiv_native.so`). Runs as the `shell` user from `/data/local/tmp/iv/bin/iv_cli`. Used for smoke tests, the device evaluation harness, and as the Plan B worker.

Environment (set by the wrapper script `/data/local/tmp/iv/run.sh`):
```
LD_LIBRARY_PATH=/data/local/tmp/iv/lib
ADSP_LIBRARY_PATH="/data/local/tmp/iv/dsp;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"
```
Model dir default `/data/local/tmp/iv/models` (`llama/`, `whisper/`), override `--models DIR`.

## Commands

All output is JSON on **stdout** (one object per line); logs go to **stderr**. Exit code 0 on success.

| Command | Output |
|---|---|
| `iv_cli info` | `EngineInfo` (PRD App. D.5) |
| `iv_cli asr --wav FILE [--lang auto\|en\|hi] [--threads N]` | `AsrResult` |
| `iv_cli llm --prompt-file FILE [--sampler greedy\|recommend] [--max-tokens N] [--stream]` | with `--stream`: lines `{"tok":"…"}` then final `{"done":true,"text":"…","stats":GenStats}`; without: only the final object |
| `iv_cli llm-batch --in IN.jsonl --out OUT.jsonl [--sampler …] [--max-tokens N]` | Loads the model once. `IN.jsonl` lines `{"id":"…","prompt":"…","max_tokens":320?,"sampler":"greedy"?}`; writes `{"id":"…","text":"…","stats":GenStats}` per line to OUT; progress on stderr. Resumable: skips ids already in OUT. |
| `iv_cli serve` | JSON-lines worker on stdin/stdout, protocol PRD §19.3 (`init`, `asr`, `generate`, `abort`, `status`, `shutdown`). |

`prompt` strings are fully rendered (PRD §13.3, `config/prompts/RENDERING.md`) — **no BOS**.

## DTOs

Exactly PRD Appendix D.5: `EngineInfo`, `AsrResult`, `GenStats`, `EngineError`. Errors: `{"error":{"code":"IV-E6xx","message":"…","retryable":true}}` with exit code 2 (retryable) or 1.
