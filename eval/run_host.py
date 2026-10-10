"""Host evaluation runner: Llama 3.2 3B Instruct via transformers + bitsandbytes NF4, greedy, laptop GPU.

Prompts are rendered by ml/common/prompt_builder.py (byte-identical to the device). Numerics differ from the
NPU (w4a16 Genie bundle) — use this for prompt iteration, report device numbers for the pitch.

  ~/venvs/train/bin/python eval/run_host.py --limit 5                       # quick check
  ~/venvs/train/bin/python eval/run_host.py --run host_base --variant base  # full set, resumable
  ~/venvs/ml/bin/python eval/score.py --run eval/results/host_base

BOS: Genie prepends BOS on device and our prompts contain none. Here we tokenize with
add_special_tokens=False and prepend exactly one <|begin_of_text|> ourselves (asserted).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

import pipeline as pl  # noqa: E402

DEFAULT_MODEL = os.environ.get("IV_HOST_MODEL", "meta-llama/Llama-3.2-3B-Instruct")
STOP_IDS = [128009, 128008, 128001]   # <|eot_id|>, <|eom_id|>, <|end_of_text|>


class HostLLM:
    def __init__(self, model_id: str, max_ctx: int = 4096):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
        self.torch = torch
        self.tok = AutoTokenizer.from_pretrained(model_id)
        q = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.float16,
                               bnb_4bit_use_double_quant=True)
        t0 = time.time()
        self.model = AutoModelForCausalLM.from_pretrained(model_id, quantization_config=q, device_map={"": 0},
                                                          torch_dtype=torch.float16, low_cpu_mem_usage=True)
        self.model.eval()
        self.load_ms = int((time.time() - t0) * 1000)
        self.bos = self.tok.convert_tokens_to_ids("<|begin_of_text|>")
        self.max_ctx = max_ctx

    def encode(self, prompt: str) -> list[int]:
        ids = self.tok(prompt, add_special_tokens=False).input_ids
        if ids and ids[0] == self.bos:
            raise ValueError("prompt already starts with BOS — renderer bug")
        ids = [self.bos] + ids
        assert ids.count(self.bos) == 1, "exactly one BOS must reach the model"
        return ids

    def generate(self, prompt: str, max_tokens: int, task: str) -> dict:
        torch = self.torch
        ids = self.encode(prompt)
        if len(ids) + max_tokens > self.max_ctx:
            return {"text": "", "stats": {"task": task, "tokens_in": len(ids), "error": "CONTEXT_OVERFLOW"}}
        x = torch.tensor([ids], device=self.model.device)
        torch.cuda.synchronize()
        t0 = time.time()
        with torch.no_grad():
            # prefill timing (TTFT ≈ prefill + first step)
            out = self.model.generate(x, attention_mask=torch.ones_like(x), max_new_tokens=max_tokens, do_sample=False,
                                      num_beams=1, eos_token_id=STOP_IDS, pad_token_id=STOP_IDS[0],
                                      temperature=None, top_p=None, top_k=None)
        torch.cuda.synchronize()
        wall = (time.time() - t0) * 1000
        gen = out[0, len(ids):].tolist()
        stop = "MAX_TOKENS"
        if gen and gen[-1] in STOP_IDS:
            gen = gen[:-1]
            stop = "EOS"
        text = self.tok.decode(gen, skip_special_tokens=False)
        return {"text": text, "stats": {"task": task, "tokens_in": len(ids), "tokens_out": len(gen),
                                        "wall_ms": round(wall, 1),
                                        "decode_tps": round(len(gen) / (wall / 1000), 2) if wall else None,
                                        "stop": stop, "engine": "host-hf-nf4"}}


def run_case(llm: HostLLM, case: dict, variant: str) -> dict:
    tr = case["transcript"]
    rec = {"id": case["id"]}
    rec["extract"] = llm.generate(pl.extract_prompt(tr, variant), pl.MAX_TOKENS["extract"], "EXTRACT")
    text = rec["extract"]["text"]
    if pl.needs_repair(text, "fill_incident_form"):
        rec["extract_repair"] = llm.generate(pl.repair_prompt("fill_incident_form", text), pl.MAX_TOKENS["repair"], "REPAIR")
        text = rec["extract_repair"]["text"]
    _, form = pl.form_from_output(case, text, tr)
    pp = pl.patch_prompt(case, form)
    if pp:
        rec["patch"] = llm.generate(pp, pl.MAX_TOKENS["patch"], "PATCH")
        if pl.needs_repair(rec["patch"]["text"], "update_incident_fields"):
            rec["patch_repair"] = llm.generate(pl.repair_prompt("update_incident_fields", rec["patch"]["text"]),
                                               pl.MAX_TOKENS["repair"], "REPAIR")
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default=str(ROOT / "eval" / "text_cases.jsonl"))
    ap.add_argument("--run", default="host_base", help="run name under eval/results/")
    ap.add_argument("--variant", default="base", choices=["base", "ft"])
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--limit", type=int)
    ap.add_argument("--ids", nargs="*")
    a = ap.parse_args()
    out_dir = ROOT / "eval" / "results" / a.run
    out_dir.mkdir(parents=True, exist_ok=True)
    pred_path = out_dir / "predictions.jsonl"
    done = set()
    if pred_path.exists():
        for line in pred_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                done.add(json.loads(line)["id"])
    cases = [c for c in pl.load_cases(a.cases, a.limit, a.ids) if c["id"] not in done]
    print(f"[host] {len(done)} done, {len(cases)} to run -> {pred_path}", file=sys.stderr)
    if not cases:
        return
    llm = HostLLM(a.model)
    print(f"[host] model loaded in {llm.load_ms} ms", file=sys.stderr)
    (out_dir / "run_info.json").write_text(json.dumps({"engine": "host-hf-nf4", "model": a.model, "variant": a.variant,
                                                       "cases": a.cases}, indent=1), encoding="utf-8")
    with open(pred_path, "a", encoding="utf-8") as f:
        for i, c in enumerate(cases):
            t0 = time.time()
            rec = run_case(llm, c, a.variant)
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            f.flush()
            print(f"[host] {i + 1}/{len(cases)} {c['id']} {time.time() - t0:.1f}s "
                  f"{rec['extract']['text'][:90]!r}", file=sys.stderr)


if __name__ == "__main__":
    main()
