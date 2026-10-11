"""LoRA / QLoRA fine-tune of Llama 3.2 3B Instruct on IncidentVoice data (PRD §27.3). Single file: also the Kaggle kernel.

  Kaggle (T4, fp16 LoRA, auto-detected):  data /kaggle/input/iv-ft-data/{train,dev}.jsonl, weights from Kaggle Models,
                                           output /kaggle/working/adapter (+ train_log.jsonl, result.json)
  Laptop (RTX 4050 6 GB, QLoRA NF4):       python ml/ft/train_lora.py --data ~/iv_ft/data --out ~/iv_ft/run --qlora

Loss on completion tokens only; completion = tool JSON + <|eot_id|>. Prompt tokenised like the device/host runner:
add_special_tokens=False + exactly one BOS. LoRA r16/a32/d0.05 on q,k,v,o,gate,up,down — never embed/lm_head (tied).
Resumable (laptop): adapter + optimizer + step saved every --save-every steps to <out>/ckpt; rerun continues.
Time guard: stops cleanly and saves when --max-minutes is reached. Thermal guard (laptop): pauses above 83 °C.
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import os
import random
import subprocess
import sys
import time

KAGGLE = os.path.isdir("/kaggle/working")


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout


if KAGGLE:
    # Kaggle image ships torchao 0.10, which recent peft rejects at LoRA injection ("only versions above 0.16")
    print(sh("pip uninstall -y -q torchao 2>&1 | tail -1"))
    try:
        import peft  # noqa: F401
    except ImportError:
        print(sh("pip install -q peft 2>&1 | tail -2"))

import torch  # noqa: E402
from transformers import AutoModelForCausalLM, AutoTokenizer, get_cosine_schedule_with_warmup  # noqa: E402
from peft import LoraConfig, PeftModel, get_peft_model  # noqa: E402

BOS, EOT = "<|begin_of_text|>", "<|eot_id|>"


def find_model():
    for c in sorted(glob.glob("/kaggle/input/**/config.json", recursive=True)):
        try:
            cfg = json.load(open(c))
        except Exception:
            continue
        if cfg.get("model_type") == "llama" and cfg.get("num_hidden_layers") == 28:
            return os.path.dirname(c)
    return None


def find_data():
    for c in glob.glob("/kaggle/input/**/train.jsonl", recursive=True):
        return os.path.dirname(c)
    return None


def load(path):
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]


def encode(tok, r, max_len):
    bos = tok.convert_tokens_to_ids(BOS)
    p = [bos] + tok(r["prompt"], add_special_tokens=False).input_ids
    c = tok(r["completion"] + EOT, add_special_tokens=False).input_ids
    ids = (p + c)[:max_len]
    lab = ([-100] * len(p) + c)[:max_len]
    return ids, lab


def batches(items, mb, rng, shuffle=True):
    # length-bucketed micro-batches (less padding), batch order shuffled
    idx = sorted(range(len(items)), key=lambda i: len(items[i][0]))
    bs = [idx[i:i + mb] for i in range(0, len(idx), mb)]
    if shuffle:
        rng.shuffle(bs)
    return bs


def collate(items, ids, pad, dev):
    L = max(len(items[i][0]) for i in ids)
    x = torch.full((len(ids), L), pad, dtype=torch.long)
    y = torch.full((len(ids), L), -100, dtype=torch.long)
    m = torch.zeros((len(ids), L), dtype=torch.long)
    for j, i in enumerate(ids):
        a, b = items[i]
        x[j, :len(a)] = torch.tensor(a)
        y[j, :len(b)] = torch.tensor(b)
        m[j, :len(a)] = 1
    return x.to(dev), y.to(dev), m.to(dev)


def gpu_temp():
    try:
        return int(sh("nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader").strip().splitlines()[0])
    except Exception:
        return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("IV_BASE", "meta-llama/Llama-3.2-3B-Instruct"))
    ap.add_argument("--data", default=None)
    ap.add_argument("--out", default=None)
    ap.add_argument("--qlora", action="store_true")
    ap.add_argument("--epochs", type=float, default=1.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--mb", type=int, default=None)
    ap.add_argument("--accum", type=int, default=None)
    ap.add_argument("--max-len", type=int, default=1280)
    ap.add_argument("--max-minutes", type=float, default=55)
    ap.add_argument("--save-every", type=int, default=20)
    ap.add_argument("--eval-every", type=int, default=50)
    a = ap.parse_args([] if KAGGLE else None)
    if KAGGLE:
        a.max_minutes = 50
    t_start = time.time()
    if KAGGLE:
        # weights only from the attached Kaggle Models source (needs the Llama licence accepted on the account)
        a.model = find_model() or a.model
        a.data = find_data()
        a.out = "/kaggle/working"
        print("model:", a.model, "data:", a.data, flush=True)
        if not a.data or not os.path.isdir(a.model):
            json.dump({"ok": False, "error": f"inputs missing: model={a.model} data={a.data}"},
                      open("/kaggle/working/result.json", "w"))
            print("INPUTS MISSING", sh("ls -R /kaggle/input | head -50"))
            return
    mb = a.mb or (4 if not a.qlora else 1)
    accum = a.accum or max(1, 16 // mb)
    os.makedirs(a.out, exist_ok=True)
    ck = os.path.join(a.out, "ckpt")
    logf = open(os.path.join(a.out, "train_log.jsonl"), "a")

    def log(**kw):
        kw["t_min"] = round((time.time() - t_start) / 60, 2)
        logf.write(json.dumps(kw) + "\n"); logf.flush(); print(kw, flush=True)

    tok = AutoTokenizer.from_pretrained(a.model)
    pad = tok.convert_tokens_to_ids(EOT)
    train = [encode(tok, r, a.max_len) for r in load(os.path.join(a.data, "train.jsonl"))]
    dev = [encode(tok, r, a.max_len) for r in load(os.path.join(a.data, "dev.jsonl"))][:48]
    log(event="data", train=len(train), dev=len(dev), max_tokens=max(len(x[0]) for x in train),
        mean_tokens=round(sum(len(x[0]) for x in train) / len(train)))

    kw = dict(torch_dtype=torch.float16, low_cpu_mem_usage=True, device_map={"": 0})
    if a.qlora:
        from transformers import BitsAndBytesConfig
        kw["quantization_config"] = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                                       bnb_4bit_use_double_quant=True,
                                                       bnb_4bit_compute_dtype=torch.float16)
    model = AutoModelForCausalLM.from_pretrained(a.model, **kw)
    model.config.use_cache = False
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    lcfg = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
                      target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"])
    resume = os.path.isfile(os.path.join(ck, "state.pt"))
    if resume:
        model = PeftModel.from_pretrained(model, ck, is_trainable=True)
    else:
        model = get_peft_model(model, lcfg)
    for n, p in model.named_parameters():  # LoRA params in fp32 (fp16 AMP master weights)
        if p.requires_grad:
            p.data = p.data.float()
    params = [p for p in model.parameters() if p.requires_grad]
    opt = torch.optim.AdamW(params, lr=a.lr, weight_decay=0.0)
    rng = random.Random(42)
    steps_per_epoch = math.ceil(len(train) / (mb * accum))
    total = max(1, int(steps_per_epoch * a.epochs))
    sched = get_cosine_schedule_with_warmup(opt, max(1, int(0.03 * total)), total)
    scaler = torch.cuda.amp.GradScaler()
    step = 0
    if resume:
        st = torch.load(os.path.join(ck, "state.pt"), map_location="cpu")
        opt.load_state_dict(st["opt"]); sched.load_state_dict(st["sched"]); scaler.load_state_dict(st["scaler"])
        step = st["step"]; rng.setstate(st["rng"])
        log(event="resume", step=step)
    log(event="start", total_steps=total, mb=mb, accum=accum, qlora=a.qlora)

    def save(final=False):
        d = os.path.join(a.out, "adapter") if final else ck
        tmp = d + ".tmp"
        model.save_pretrained(tmp)
        if not final:
            torch.save({"opt": opt.state_dict(), "sched": sched.state_dict(), "scaler": scaler.state_dict(),
                        "step": step, "rng": rng.getstate()}, os.path.join(tmp, "state.pt"))
        if os.path.isdir(d):
            os.system(f"rm -rf '{d}.old' && mv '{d}' '{d}.old'")
        os.rename(tmp, d)
        os.system(f"rm -rf '{d}.old'")

    @torch.no_grad()
    def dev_loss():
        model.eval()
        tot, n = 0.0, 0
        for ids in batches(dev, mb, rng, shuffle=False):
            x, y, m = collate(dev, ids, pad, model.device)
            with torch.autocast("cuda", dtype=torch.float16):
                tot += model(input_ids=x, attention_mask=m, labels=y).loss.item(); n += 1
        model.train()
        return round(tot / max(1, n), 4)

    # deterministic schedule of micro-batches across epochs; skip what's done on resume
    plan = []
    ep = 0
    while len(plan) < total * accum:
        plan += batches(train, mb, random.Random(1000 + ep)); ep += 1
    plan = plan[: total * accum]
    model.train()
    stopped = None
    t_step = t_loop = time.time()
    while step < total:
        if (time.time() - t_start) / 60 > a.max_minutes:
            stopped = "time_guard"; break
        if not KAGGLE and gpu_temp() >= 83:
            log(event="thermal_pause", temp=gpu_temp()); time.sleep(60); continue
        tl = 0.0
        for k in range(accum):
            x, y, m = collate(train, plan[step * accum + k], pad, model.device)
            with torch.autocast("cuda", dtype=torch.float16):
                loss = model(input_ids=x, attention_mask=m, labels=y).loss / accum
            scaler.scale(loss).backward()
            tl += loss.item()
        scaler.unscale_(opt)
        torch.nn.utils.clip_grad_norm_(params, 1.0)
        scaler.step(opt); scaler.update(); opt.zero_grad(set_to_none=True); sched.step()
        step += 1
        if step == 4 and not resume:
            # fit the cosine schedule into the time budget (Kaggle cap): shrink total steps if needed
            sps = (time.time() - t_loop) / 4
            budget = a.max_minutes * 60 - (time.time() - t_start) - 120
            fit = int(budget / sps) + 4
            if fit < total:
                total = max(8, fit)
                for g in opt.param_groups:
                    g["lr"] = a.lr
                sched = get_cosine_schedule_with_warmup(opt, max(1, int(0.03 * total)), total)
                for _ in range(step):
                    sched.step()
                log(event="schedule_shrunk", total_steps=total, s_per_step=round(sps, 1))
        if step % 5 == 0 or step == 1:
            log(event="step", step=step, loss=round(tl, 4), lr=sched.get_last_lr()[0],
                s_per_step=round((time.time() - t_step) / (1 if step == 1 else 5), 1))
            t_step = time.time()
        if a.eval_every and step % a.eval_every == 0:
            log(event="dev", step=step, dev_loss=dev_loss())
        if not KAGGLE and step % a.save_every == 0:
            save()
    dl = dev_loss()
    save(final=True)
    if not KAGGLE:
        save()
    res = {"ok": True, "steps": step, "total_steps": total, "stopped": stopped, "dev_loss": dl,
           "minutes": round((time.time() - t_start) / 60, 1)}
    # quick JSON-validity probe on a few dev extract prompts (greedy)
    try:
        model.eval(); model.config.use_cache = True
        rows = [r for r in load(os.path.join(a.data, "dev.jsonl")) if r["task"] == "extract"][:8]
        ok = 0
        for r in rows:
            ids = [tok.convert_tokens_to_ids(BOS)] + tok(r["prompt"], add_special_tokens=False).input_ids
            x = torch.tensor([ids], device=model.device)
            with torch.no_grad(), torch.autocast("cuda", dtype=torch.float16):
                out = model.generate(input_ids=x, attention_mask=torch.ones_like(x), max_new_tokens=400,
                                     do_sample=False, eos_token_id=[128009, 128001], pad_token_id=128009)
            txt = tok.decode(out[0, len(ids):], skip_special_tokens=True)
            try:
                json.loads(txt); ok += 1
            except Exception:
                pass
        res["dev_json_valid"] = f"{ok}/{len(rows)}"
        res["sample"] = txt[:400]
    except Exception as e:
        res["gen_error"] = repr(e)
    json.dump(res, open(os.path.join(a.out, "result.json"), "w"), indent=1)
    log(event="done", **{k: v for k, v in res.items() if k != "sample"})


if __name__ == "__main__":
    main()
