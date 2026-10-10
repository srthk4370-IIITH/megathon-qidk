# STATUS.md — what exists right now

Update this whenever something is built, breaks, or is decided. Feature IDs (F-xx) and milestones (M0–M4) refer to `PRD.md` §5 and §35.

_Last updated: 2026-10-10 (Saarthak branch)_

## Overall

| Milestone | Status |
|---|---|
| M0 — contracts frozen, exports running | Not started (hackathon clock not started) |
| M1 — speech → transcript → tool-call JSON on board | Not started |
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
| WSL VHDX compaction (frees ~110 GB on C:) | Sparse flag removed; user re-runs diskpart as admin | User |

## By branch

| Branch | Area | Built so far |
|---|---|---|
| `Saarthak` | ML / NPU | Docs only. No models exported yet. |
| Android (TBD) | App + dashboard | — |

## Board / environment

| Item | State |
|---|---|
| Board clock / timezone | Wrong (2024, GMT) — fix at H0 (PRD §26.3) |
| QAIRT 2.50 SDK | Not downloaded |
| Llama 3.2 3B V79 bundle | Not exported |
| Whisper-small V79 binaries | Not exported |
| Plan A vs Plan B | Undecided (probe at ~H3) |
