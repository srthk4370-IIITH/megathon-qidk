"""Build an AIMET-ONNX checkpoint for the fine-tuned model that `qai-hub-models export llama_v3_2_3b_instruct
--checkpoint <dir>` accepts (PRD §27.6; qai-hub-models 0.63, run in ~/venvs/qaihm_pin).

The stock DEFAULT path (LlamaDynamicQuantizablePreSplitMixin.resolve_default_checkpoint) downloads ONLY the published
w4a16 `model.encodings` and exports the ONNX graph from the FP torch model. We run the very same code path with our
merged FP weights instead of the HF repo → identical graph/node names (pinned torch/transformers in that venv), same
published encodings, our weights. Then widen per-channel 4-bit weight scales that the fine-tune pushed past the
published range (refresh_param_scales; only widens, never shrinks).

  ~/venvs/qaihm_pin/bin/python ml/ft/build_ft_ckpt.py --merged ~/iv_ft/merged --out ~/iv_ft/ft_ckpt_w4a16
Steps are idempotent (markers in <out>): ONNX_OK, SCALES_OK.
"""
import argparse
import json
import os
import shutil
import sys
from pathlib import Path

import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("--merged", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--stock-encodings",
                default=str(Path.home() / ".qaihm/qai-hub-models/models/llama_v3_2_3b_instruct/v7/w4a16/model.encodings"))
a = ap.parse_args()
out = Path(a.out).expanduser()
out.mkdir(parents=True, exist_ok=True)

if not (out / "ONNX_OK").exists():
    import torch
    from qai_hub_models.models.llama_v3_2_3b_instruct.model import (
        Llama3_2_3B_PreSplit as FP, Llama3_2_3B_QuantizablePreSplit as Q)
    enc = Path(a.stock_encodings)
    if not enc.exists():  # fetch the published encodings the same way the stock export does
        from qai_hub_models.utils.asset_loaders import CachedWebModelAsset
        enc = Path(CachedWebModelAsset.from_asset_store(Q.model_id, Q.model_asset_version, "w4a16/model.encodings").fetch())
    for f in ("model_dynamic.onnx", "model.data", "model.onnx"):
        (out / f).unlink(missing_ok=True)  # partial previous attempt
    shutil.copy(enc, out / "model.encodings")
    fp = FP.from_pretrained(checkpoint=str(Path(a.merged).expanduser()), host_device=torch.device("cpu"))
    Q.create_onnx_models(checkpoint=str(out), fp_model=fp, context_length=fp.context_length,
                         host_device=torch.device("cpu"), llm_io_type=fp.llm_io_type)
    Q.save_tokenizer_and_config(checkpoint=str(out), fp_model=fp)
    json.dump({"precision": "w4a16"}, open(out / "args.json", "w"))
    assert (out / "model_dynamic.onnx").exists() and (out / "model.data").exists(), os.listdir(out)
    (out / "ONNX_OK").write_text("ok")
    del fp
    print("onnx export ok", flush=True)

if not (out / "SCALES_OK").exists():
    import onnx
    m = onnx.load(str(out / "model_dynamic.onnx"), load_external_data=False)
    loc = {}
    for t in m.graph.initializer:
        if t.data_location == onnx.TensorProto.EXTERNAL:
            d = {e.key: e.value for e in t.external_data}
            loc[t.name] = (d["location"], int(d.get("offset", 0)), int(d["length"]), t.data_type, tuple(t.dims))
    encs = json.load(open(out / "model.encodings"))
    pe = encs["param_encodings"]
    items = pe if isinstance(pe, list) else [dict(name=k, **v) for k, v in pe.items()]
    mm = {}
    widened = tensors = missing = 0
    worst = 0.0
    for e in items:
        if e.get("enc_type") != "PER_CHANNEL" or str(e.get("bw")) != "4":
            continue
        if e["name"] not in loc:
            missing += 1
            continue
        fn, off, ln, dt, dims = loc[e["name"]]
        if fn not in mm:
            mm[fn] = np.memmap(out / fn, dtype=np.uint8, mode="r")
        npdt = {onnx.TensorProto.FLOAT: np.float32, onnx.TensorProto.FLOAT16: np.float16}[dt]
        w = np.frombuffer(mm[fn][off:off + ln].tobytes(), dtype=npdt).reshape(dims).astype(np.float32)
        s = np.asarray(e["scale"], dtype=np.float64)
        ax = [i for i, d in enumerate(dims) if d == len(s)]
        if not ax:
            missing += 1
            continue
        ax = ax[0]
        red = tuple(i for i in range(w.ndim) if i != ax)
        need = np.maximum(w.max(axis=red) / 7.0, -w.min(axis=red) / 8.0).astype(np.float64)
        grow = need > s * 1.0001
        tensors += 1
        if grow.any():
            widened += int(grow.sum())
            worst = max(worst, float((need[grow] / s[grow]).max()))
            s = np.where(grow, need, s)
            e["scale"] = [float(x) for x in s]
    if not isinstance(pe, list):
        encs["param_encodings"] = {x.pop("name"): x for x in items}
    shutil.copy(out / "model.encodings", out / "model.encodings.published")
    json.dump(encs, open(out / "model.encodings", "w"), indent=1)
    rep = {"tensors_checked": tensors, "channels_widened": widened, "max_ratio": round(worst, 3), "missing": missing}
    (out / "SCALES_OK").write_text(json.dumps(rep))
    print("scale refresh:", rep, flush=True)
    if missing > tensors:
        sys.exit("encoding names do not match the ONNX initializers — graph mismatch")
