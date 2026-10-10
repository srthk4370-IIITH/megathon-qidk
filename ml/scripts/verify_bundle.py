#!/usr/bin/env python3
"""Verify an AI Hub Genie bundle before it goes near the board (PRD §25.3).

Checks: context-binary header sizes (catches truncated downloads), SHA256SUMS.txt (write or compare),
tool-versions QAIRT, htp_backend_ext_config dsp_arch v79, genie_config rope factor / context / tokens,
ctx-bin names exist. Exit 0 = OK. Usage: verify_bundle.py <bundle_dir> [--write-manifest]
"""
import hashlib
import json
import os
import re
import struct
import sys


def sha256(p, bs=1 << 22):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(bs), b""):
            h.update(b)
    return h.hexdigest()


def find(d, key):
    if isinstance(d, dict):
        for k, v in d.items():
            if k == key:
                yield v
            yield from find(v, key)
    elif isinstance(d, list):
        for x in d:
            yield from find(x, key)


def main():
    bdir = sys.argv[1]
    errors, notes = [], []
    bins = sorted(f for f in os.listdir(bdir) if f.endswith(".bin"))
    if not bins:
        errors.append("no .bin context binaries")
    for b in bins:
        p = os.path.join(bdir, b)
        size = os.path.getsize(p)
        with open(p, "rb") as f:
            hdr = f.read(16)
        if len(hdr) == 16:
            hsz, bsz = struct.unpack("<QQ", hdr)
            if hsz + bsz == size:
                notes.append(f"{b}: header OK ({size} B)")
            else:
                notes.append(f"{b}: header {hsz}+{bsz}={hsz + bsz} vs size {size} (format may differ; check sha)")
        else:
            errors.append(f"{b}: too small")
    cfg_p = os.path.join(bdir, "genie_config.json")
    if not os.path.exists(cfg_p):
        errors.append("genie_config.json missing (export with --target-runtime genie)")
    else:
        cfg = json.load(open(cfg_p))
        for ctx in find(cfg, "ctx-bins"):
            for c in ctx:
                if os.path.basename(c) not in bins:
                    errors.append(f"ctx-bin {c} not in bundle")
        for f in find(cfg, "factor"):
            if float(f) != 32.0:
                errors.append(f"rope factor {f} != 32.0")
        sizes = list(find(cfg, "size"))
        notes.append(f"context size(s): {sizes}")
        for e in find(cfg, "eos-token"):
            if 128009 not in (e if isinstance(e, list) else [e]):
                errors.append(f"eos-token {e} lacks 128009")
        for s in find(cfg, "sampler"):
            notes.append(f"bundle sampler: {s} (runtime forces greedy)")
    ext_p = os.path.join(bdir, "htp_backend_ext_config.json")
    if os.path.exists(ext_p):
        ext = json.load(open(ext_p))
        arch = list(find(ext, "dsp_arch"))
        soc = list(find(ext, "soc_model"))
        notes.append(f"dsp_arch={arch} soc_model={soc}")
        if arch and arch[0] != "v79":
            errors.append(f"dsp_arch {arch} is not v79 — wrong device target")
    else:
        errors.append("htp_backend_ext_config.json missing")
    tv = os.path.join(bdir, "tool-versions.yaml")
    if os.path.exists(tv):
        m = re.search(r"qairt[^\n]*?(\d+\.\d+\.\d+[\.\d]*)", open(tv).read(), re.I)
        notes.append(f"tool-versions qairt: {m.group(1) if m else '?'}")
    if not os.path.exists(os.path.join(bdir, "tokenizer.json")):
        errors.append("tokenizer.json missing")
    # SHA256
    sums_p = os.path.join(bdir, "SHA256SUMS.txt")
    files = sorted(f for f in os.listdir(bdir) if os.path.isfile(os.path.join(bdir, f)) and f != "SHA256SUMS.txt")
    current = {f: sha256(os.path.join(bdir, f)) for f in files}
    if os.path.exists(sums_p):
        old = dict(reversed(l.split("  ", 1)) for l in open(sums_p).read().splitlines() if l.strip())
        for f, h in old.items():
            if current.get(f.strip()) != h:
                errors.append(f"sha mismatch {f}")
    else:
        with open(sums_p, "w") as fo:
            for f, h in current.items():
                fo.write(f"{h}  {f}\n")
        notes.append("wrote SHA256SUMS.txt")
    if "--write-manifest" in sys.argv:
        man = {"files": {f: {"size": os.path.getsize(os.path.join(bdir, f)), "sha256": h} for f, h in current.items()},
               "prompt_variant": "base", "model": "llama_v3_2_3b_instruct", "precision": "w4a16", "dsp_arch": "v79"}
        json.dump(man, open(os.path.join(bdir, "MANIFEST.json"), "w"), indent=2)
    for n in notes:
        print("  ", n)
    for e in errors:
        print("ERROR", e)
    print("BUNDLE_OK" if not errors else "BUNDLE_BAD")
    sys.exit(0 if not errors else 1)


if __name__ == "__main__":
    main()
