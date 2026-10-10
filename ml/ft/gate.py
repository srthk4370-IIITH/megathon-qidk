"""Host pre-gate (PRD §27.5): fine-tuned (eval/results/ft_dev) vs base (eval/results/prompt_final) on eval/dev_ids.txt.
Exit 0 = pass (field F1 >= base + 0.05 and JSON-valid-with-repair >= base). Last line = one-line summary."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval"))
sys.path.insert(0, str(ROOT))
import score as sc  # noqa: E402

ids = [l.strip() for l in open(ROOT / "eval/dev_ids.txt") if l.strip()]
cases = [c for c in sc.load_jsonl(ROOT / "eval/text_cases.jsonl") if c["id"] in set(ids)]


def run(name):
    preds = [p for p in sc.load_jsonl(ROOT / "eval/results" / name / "predictions.jsonl") if p["id"] in set(ids)]
    have = {p["id"] for p in preds}
    m, pc = sc.score([c for c in cases if c["id"] in have], preds)
    return m, len(preds)


b, nb = run("prompt_final")
import os  # noqa: E402
FT = os.environ.get("IV_GATE_FT", "ft_dev")
f, nf = run(FT)
keys = ["field_f1_micro", "type_accuracy", "json_valid_without_repair", "json_valid_with_repair", "json_strict",
        "hallucination_rate"]
for k in keys:
    print(f"{k:28s} base {b.get(k, 0):.3f}  ft {f.get(k, 0):.3f}")
ok = nf >= len(ids) and f["field_f1_micro"] >= b["field_f1_micro"] + 0.05 and \
    f["json_valid_with_repair"] >= b["json_valid_with_repair"]
json.dump({"base": {k: b.get(k) for k in keys}, "ft": {k: f.get(k) for k in keys}, "pass": ok, "n": nf},
          open(ROOT / "eval/results" / FT / "gate.json", "w"), indent=1)
print(f"{'PASS' if ok else 'FAIL'} dev n={nf}: F1 {b['field_f1_micro']:.3f}->{f['field_f1_micro']:.3f}, "
      f"type {b['type_accuracy']:.2f}->{f['type_accuracy']:.2f}, json {b['json_valid_with_repair']:.2f}->"
      f"{f['json_valid_with_repair']:.2f}, halluc {b.get('hallucination_rate', 0):.2f}->{f.get('hallucination_rate', 0):.2f}")
sys.exit(0 if ok else 1)
