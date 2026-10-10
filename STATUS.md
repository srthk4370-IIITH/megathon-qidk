# STATUS.md — what exists right now

Update this whenever something is built, breaks, or is decided. Feature IDs (F-xx) and milestones (M0–M4) refer to `PRD.md` §5 and §35.

_Last updated: 2026-10-10 21:20 IST (Saarthak branch)_

## Overall

| Milestone | Status |
|---|---|
| M0 — contracts frozen, exports running | **Exports running** (laptop + Kaggle); contracts = PRD App. A–D, not yet committed as `config/` files |
| M1 — approach doc | `M1.md` written |
| M2 — full conversation on board | Not started |
| M3 — all P0 features | Not started |
| M4 — release candidate | Not started |

## Done

- `PRD.md` v1.0 (spec), `HANDBOOK.md` (history), `CHANGE.md`, `STATUS.md`, `.gitignore`.
- Board (SM8750P / HTP V79) inventoried; `/data/local/tmp` wiped → 29 GB free.
- Old QAIRT 2.47 V79 runtime backed up locally to `_board_runtime_backup/` (git-ignored).
- Laptop caches and old WSL artifacts cleaned.

## In progress / blocked

| Item | State | Owner |
|---|---|---|
| Llama 3.2 3B V79 Genie export | Running: laptop (since 21:03) + Kaggle TPU kernel `iv-llama-v79-export` | ML |
| QAIRT 2.50 SDK | Downloading (public URL) to WSL `~/qairt/` | ML |

## By branch

| Branch | Area | Built so far |
|---|---|---|
| `Saarthak` | ML / NPU | Docs only. No models exported yet. |
| `renny` (App) | App + dashboard | `config/` (verbatim from PRD), `handover_app.md`. No Android code yet — toolchain (JDK/SDK) not installed on this Mac. |

## Board / environment

| Item | State |
|---|---|
| Board clock / timezone | Fixed 21:03 IST |
| QAIRT 2.50 SDK | Downloading |
| Llama 3.2 3B V79 bundle | Export running (2 hosts) |
| Whisper | P0 = whisper.cpp CPU (not started); NPU via ORT-QNN is P1 |
| Plan A vs Plan B | Undecided (probe at ~H3) |
