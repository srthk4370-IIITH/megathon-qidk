"""Merge a LoRA adapter into Llama 3.2 3B Instruct → fp16 safetensors (PRD §27.4). CPU only (fits 9–12 GB WSL + swap).

  ~/venvs/train/bin/python ml/ft/merge.py --adapter ~/iv_ft/adapter --out ~/iv_ft/merged
Checks: tie_word_embeddings, 28 layers, hidden 3072, 24/8 heads, rope llama3 factor 32, no lm_head.weight saved,
chat_template present. Writes <out>/MERGE_OK on success (idempotent: skips if present).
"""
import argparse
import json
import os
import shutil
import sys

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="meta-llama/Llama-3.2-3B-Instruct")
ap.add_argument("--adapter", required=True)
ap.add_argument("--out", required=True)
a = ap.parse_args()
if os.path.isfile(os.path.join(a.out, "MERGE_OK")):
    print("already merged"); sys.exit(0)
tmp = a.out + ".tmp"
shutil.rmtree(tmp, ignore_errors=True)
m = AutoModelForCausalLM.from_pretrained(a.base, torch_dtype=torch.float16, low_cpu_mem_usage=True)  # no device_map: peft would re-dispatch + disk-offload
emb0 = m.get_input_embeddings().weight[:4, :8].clone()
if hasattr(m, "hf_device_map"):
    del m.hf_device_map  # else peft 0.13 re-dispatches with disk offload
m = PeftModel.from_pretrained(m, a.adapter, torch_device="cpu").merge_and_unload()  # CPU only (6 GB GPU would force offload)
assert torch.equal(emb0, m.get_input_embeddings().weight[:4, :8]), "embeddings changed — adapter must not touch embed/lm_head"
assert m.lm_head.weight.data_ptr() == m.get_input_embeddings().weight.data_ptr(), "lm_head no longer tied"
m.save_pretrained(tmp, safe_serialization=True, max_shard_size="5GB")
tok = AutoTokenizer.from_pretrained(a.base)
tok.save_pretrained(tmp)
c = json.load(open(os.path.join(tmp, "config.json")))
tc = json.load(open(os.path.join(tmp, "tokenizer_config.json")))
chk = {"tie": c.get("tie_word_embeddings") is True, "layers": c["num_hidden_layers"] == 28, "hidden": c["hidden_size"] == 3072,
       "heads": (c["num_attention_heads"], c["num_key_value_heads"]) == (24, 8),
       "rope": (c.get("rope_scaling") or {}).get("factor") == 32.0, "chat_template": bool(tc.get("chat_template"))}
from safetensors import safe_open  # noqa: E402
keys = set()
for f in os.listdir(tmp):
    if f.endswith(".safetensors"):
        with safe_open(os.path.join(tmp, f), "pt") as s:
            keys |= set(s.keys())
chk["no_lm_head"] = "lm_head.weight" not in keys
print(json.dumps(chk))
assert all(chk.values()), chk
shutil.rmtree(a.out, ignore_errors=True)
os.rename(tmp, a.out)
open(os.path.join(a.out, "MERGE_OK"), "w").write(json.dumps(chk))
print("merged ->", a.out)
