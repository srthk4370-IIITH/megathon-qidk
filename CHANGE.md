# CHANGE.md — cross-branch change log

Purpose: announce any change on one branch that affects another branch (contracts, schemas, file paths, APIs, model files, board setup). Read this before starting work; append before pushing a change that others depend on.

**Branches**
| Branch | Area | Handover file |
|---|---|---|
| `Saarthak` | ML / NPU: models, export, native inference, prompts, taxonomy, eval | `handover_Saarthak.md` |
| (Android branch, name TBD) | Android app | `handover_<branch>.md` |
| (Web/Dashboard branch, name TBD) | Web / dashboard | `handover_<branch>.md` |

**Entry format**
```
## YYYY-MM-DD HH:MM — <branch> — <short title>
- What changed:
- Who is affected (branches / PRD sections):
- Action needed by others:
- Commit:
```

---

## 2026-10-10 — Saarthak — Project docs baseline
- What changed: `PRD.md` v1.0 is the single source of truth. Shared contracts live in PRD Appendix A (`config/taxonomy.json`), B (`config/sop.json`), C (prompts), D (tool-call schemas + engine DTOs), E (SQL DDL).
- Who is affected: all branches.
- Action needed by others: any change to a field key, enum value, schema or DTO must be announced here **and** made in PRD.md in the same commit.
- Commit: 8ff065e

## 2026-10-10 — Saarthak — Open question: dashboard location
- What changed: team split now has a separate Web/Dashboard branch, but PRD D-44 / §23 places the dashboard **inside the Android app**.
- Who is affected: Android, Web/Dashboard.
- Action needed: team decides (in-app Compose dashboard vs web dashboard served on-device vs both). PRD §23 and D-44 get updated once decided.
