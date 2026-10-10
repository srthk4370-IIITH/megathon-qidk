"""Device evaluation runner: drives `iv_cli` on the QIDK over adb (contract: native/IV_CLI.md).

Text cases (identical numerics to the app):
  python eval/run_device.py --run dev_base                    # all of eval/text_cases.jsonl, resumable
  python eval/run_device.py --run dev_base --limit 10
Clips (ASR, then optionally end-to-end ASR -> LLM):
  python eval/run_device.py --run dev_clips --asr             # eval/clips/*.wav -> asr.jsonl
  python eval/run_device.py --run dev_clips --asr --e2e       # + extraction on the ASR text
Then: python eval/score.py --run eval/results/dev_base   (clips: add --cases eval/clips.jsonl)

Stages (each one `iv_cli llm-batch` call, model loaded once per stage; resumable because iv_cli skips ids
already present in its OUT file, and we keep the remote OUT between invocations):
  1 extract  2 repair(extract)  3 patch (prompt built from the parsed extraction)  4 repair(patch)

adb: Windows adb.exe is used from WSL (WSL's own adb cannot see the board). Local file arguments are
converted with `wslpath -w`; MSYS_NO_PATHCONV=1 is set so Git Bash never rewrites /data/... paths.
Override with IV_ADB=/path/to/adb(.exe), IV_REMOTE=/data/local/tmp/iv, IV_SERIAL=<serial>.
The on-device wrapper /data/local/tmp/iv/run.sh sets LD_LIBRARY_PATH/ADSP_LIBRARY_PATH and execs iv_cli.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

import pipeline as pl  # noqa: E402

DEFAULT_ADB_WSL = "/mnt/c/Users/hp/Desktop/qidk/Code/platform-tools/adb.exe"
DEFAULT_ADB_WIN = r"C:\Users\hp\Desktop\qidk\Code\platform-tools\adb.exe"
REMOTE = os.environ.get("IV_REMOTE", "/data/local/tmp/iv")
RUN_SH = f"{REMOTE}/run.sh"


def is_wsl() -> bool:
    try:
        return "microsoft" in Path("/proc/version").read_text().lower()
    except Exception:
        return False


class Adb:
    def __init__(self):
        if os.environ.get("IV_ADB"):
            self.exe = os.environ["IV_ADB"]
        elif os.name == "nt":
            self.exe = DEFAULT_ADB_WIN
        elif is_wsl() and Path(DEFAULT_ADB_WSL).exists():
            self.exe = DEFAULT_ADB_WSL
        else:
            self.exe = "adb"
        self.win_exe = self.exe.lower().endswith(".exe") and os.name != "nt"
        self.serial = os.environ.get("IV_SERIAL")
        self.env = dict(os.environ, MSYS_NO_PATHCONV="1", MSYS2_ARG_CONV_EXCL="*")

    def local(self, p: Path | str) -> str:
        """Local path as adb.exe understands it (Windows path when calling adb.exe from WSL)."""
        p = str(Path(p).resolve())
        if self.win_exe:
            return subprocess.check_output(["wslpath", "-w", p], text=True).strip()
        return p

    def _base(self):
        return [self.exe] + (["-s", self.serial] if self.serial else [])

    def run(self, *args, capture=True, timeout=None, check=True) -> subprocess.CompletedProcess:
        cmd = self._base() + list(args)
        r = subprocess.run(cmd, env=self.env, capture_output=capture, text=True, timeout=timeout,
                           encoding="utf-8", errors="replace")
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} -> {r.returncode}\n{(r.stderr or '')[-2000:]}")
        return r

    def shell(self, command: str, capture=True, timeout=None, check=True):
        # one string: adb joins args with spaces and hands them to the remote /system/bin/sh
        return self.run("shell", command, capture=capture, timeout=timeout, check=check)

    def push(self, local, remote):
        return self.run("push", self.local(local), remote)

    def pull(self, remote, local):
        Path(local).parent.mkdir(parents=True, exist_ok=True)
        return self.run("pull", remote, self.local(local))

    def exists(self, remote) -> bool:
        r = self.shell(f"[ -e {shlex.quote(remote)} ] && echo yes || echo no", check=False)
        return "yes" in (r.stdout or "")


def read_jsonl(p: Path) -> list[dict]:
    if not p.exists():
        return []
    out = []
    for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return out


def write_jsonl(p: Path, rows):
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def llm_batch(adb: Adb, out_dir: Path, rdir: str, stage: str, items: list[dict], timeout: int) -> dict[str, dict]:
    """Run one llm-batch stage. items: {"id","prompt","max_tokens","sampler"}. Returns id -> result."""
    local_out = out_dir / f"{stage}.out.jsonl"
    have = {r["id"]: r for r in read_jsonl(local_out) if "id" in r}
    todo = [it for it in items if it["id"] not in have]
    if not todo:
        print(f"[device] stage {stage}: all {len(items)} cached", file=sys.stderr)
        return have
    local_in = out_dir / f"{stage}.in.jsonl"
    write_jsonl(local_in, items)  # full list: iv_cli skips ids already in the remote OUT
    rin, rout = f"{rdir}/{stage}.in.jsonl", f"{rdir}/{stage}.out.jsonl"
    adb.push(local_in, rin)
    max_tokens = max(it["max_tokens"] for it in items)
    t0 = time.time()
    print(f"[device] stage {stage}: {len(todo)} prompt(s) on device ...", file=sys.stderr)
    r = adb.shell(f"sh {RUN_SH} llm-batch --in {rin} --out {rout} --max-tokens {max_tokens}",
                  capture=False, timeout=timeout, check=False)
    if r.returncode != 0:
        print(f"[device] stage {stage}: iv_cli exit {r.returncode} (partial results kept; re-run to resume)",
              file=sys.stderr)
    if adb.exists(rout):
        adb.pull(rout, local_out)
    have = {r_["id"]: r_ for r_ in read_jsonl(local_out) if "id" in r_}
    print(f"[device] stage {stage}: {len(have)}/{len(items)} results in {time.time() - t0:.0f}s", file=sys.stderr)
    return have


def run_text(adb: Adb, cases: list[dict], out_dir: Path, rdir: str, variant: str, timeout: int,
             transcripts: dict[str, str] | None = None):
    tr = {c["id"]: (transcripts or {}).get(c["id"], c["transcript"]) for c in cases}
    T = pl.MAX_TOKENS
    # 1 extract
    items = [{"id": f"{c['id']}::extract", "prompt": pl.extract_prompt(tr[c["id"]], variant),
              "max_tokens": T["extract"], "sampler": "greedy"} for c in cases]
    ex = llm_batch(adb, out_dir, rdir, "s1_extract", items, timeout)
    # 2 repair
    rep_items = []
    for c in cases:
        r = ex.get(f"{c['id']}::extract")
        if r and pl.needs_repair(r.get("text"), "fill_incident_form"):
            rep_items.append({"id": f"{c['id']}::extract_repair", "prompt": pl.repair_prompt("fill_incident_form", r["text"]),
                              "max_tokens": T["repair"], "sampler": "greedy"})
    rep = llm_batch(adb, out_dir, rdir, "s2_repair", rep_items, timeout) if rep_items else {}
    # 3 patch
    patch_items = []
    for c in cases:
        r = ex.get(f"{c['id']}::extract")
        if r is None or not c.get("followup"):
            continue
        text = (rep.get(f"{c['id']}::extract_repair") or r).get("text")
        _, form = pl.form_from_output(c, text, tr[c["id"]])
        patch_items.append({"id": f"{c['id']}::patch", "prompt": pl.patch_prompt(c, form),
                            "max_tokens": T["patch"], "sampler": "greedy"})
    pa = llm_batch(adb, out_dir, rdir, "s3_patch", patch_items, timeout) if patch_items else {}
    # 4 repair patch
    prep_items = []
    for c in cases:
        r = pa.get(f"{c['id']}::patch")
        if r and pl.needs_repair(r.get("text"), "update_incident_fields"):
            prep_items.append({"id": f"{c['id']}::patch_repair",
                               "prompt": pl.repair_prompt("update_incident_fields", r["text"]),
                               "max_tokens": T["repair"], "sampler": "greedy"})
    prep = llm_batch(adb, out_dir, rdir, "s4_patch_repair", prep_items, timeout) if prep_items else {}
    # combine
    preds = []
    for c in cases:
        cid = c["id"]
        if f"{cid}::extract" not in ex:
            continue
        rec = {"id": cid}
        if transcripts and cid in transcripts:
            rec["transcript"] = transcripts[cid]
        for key, src in (("extract", ex), ("extract_repair", rep), ("patch", pa), ("patch_repair", prep)):
            r = src.get(f"{cid}::{key}")
            if r is not None:
                rec[key] = {"text": r.get("text", ""), "stats": r.get("stats")}
                if "error" in r:
                    rec[key]["error"] = r["error"]
        preds.append(rec)
    write_jsonl(out_dir / "predictions.jsonl", preds)
    print(f"[device] wrote {len(preds)} predictions -> {out_dir / 'predictions.jsonl'}", file=sys.stderr)


def run_asr(adb: Adb, clips: list[dict], out_dir: Path, rdir: str, lang: str, timeout: int) -> dict[str, str]:
    asr_path = out_dir / "asr.jsonl"
    have = {r["id"]: r for r in read_jsonl(asr_path)}
    for c in clips:
        if c["id"] in have:
            continue
        wav = ROOT / "eval" / "clips" / f"{c['id']}.wav"
        if not wav.exists():
            print(f"[device] missing {wav.name} (run eval/convert_clips.sh)", file=sys.stderr)
            continue
        rwav = f"{rdir}/clips/{wav.name}"
        adb.shell(f"mkdir -p {rdir}/clips")
        adb.push(wav, rwav)
        t0 = time.time()
        r = adb.shell(f"sh {RUN_SH} asr --wav {rwav} --lang {lang}", timeout=timeout, check=False)
        lines = [l for l in (r.stdout or "").splitlines() if l.strip().startswith("{")]
        obj = json.loads(lines[-1]) if lines else {"error": {"code": "NO_OUTPUT", "message": (r.stderr or "")[-300:]}}
        obj["id"] = c["id"]
        obj["exit"] = r.returncode
        obj["host_wall_ms"] = int((time.time() - t0) * 1000)
        have[c["id"]] = obj
        with open(asr_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(obj, ensure_ascii=False) + "\n")
        print(f"[device] asr {c['id']}: {obj.get('text', obj.get('error'))!r}", file=sys.stderr)
    return {k: v.get("text", "") for k, v in have.items() if v.get("text")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="dev_base")
    ap.add_argument("--cases", help="default: eval/text_cases.jsonl (eval/clips.jsonl with --asr)")
    ap.add_argument("--variant", default="base", choices=["base", "ft"])
    ap.add_argument("--limit", type=int)
    ap.add_argument("--ids", nargs="*")
    ap.add_argument("--asr", action="store_true", help="run iv_cli asr on eval/clips/*.wav")
    ap.add_argument("--asr-lang", default="auto", choices=["auto", "en", "hi"])
    ap.add_argument("--e2e", action="store_true", help="with --asr: also run extraction on the ASR text")
    ap.add_argument("--timeout", type=int, default=6 * 3600)
    ap.add_argument("--dry-run", action="store_true", help="only render the stage-1 prompts locally")
    a = ap.parse_args()
    cases_path = a.cases or str(ROOT / "eval" / ("clips.jsonl" if a.asr else "text_cases.jsonl"))
    cases = pl.load_cases(cases_path, a.limit, a.ids)
    out_dir = ROOT / "eval" / "results" / a.run
    out_dir.mkdir(parents=True, exist_ok=True)
    if a.dry_run:
        items = [{"id": f"{c['id']}::extract", "prompt": pl.extract_prompt(c["transcript"], a.variant),
                  "max_tokens": pl.MAX_TOKENS["extract"], "sampler": "greedy"} for c in cases]
        write_jsonl(out_dir / "s1_extract.in.jsonl", items)
        print(f"rendered {len(items)} prompts -> {out_dir / 's1_extract.in.jsonl'}")
        return
    adb = Adb()
    rdir = f"{REMOTE}/eval/{a.run}"
    adb.shell(f"mkdir -p {rdir}")
    info = adb.shell(f"sh {RUN_SH} info", check=False)
    (out_dir / "engine_info.json").write_text(info.stdout or "", encoding="utf-8")
    (out_dir / "run_info.json").write_text(json.dumps({"engine": "device-iv_cli", "variant": a.variant,
                                                       "cases": cases_path, "remote": rdir}, indent=1), encoding="utf-8")
    transcripts = None
    if a.asr:
        transcripts = run_asr(adb, cases, out_dir, rdir, a.asr_lang, a.timeout)
        if not a.e2e:
            return
        cases = [c for c in cases if c["id"] in transcripts]
    run_text(adb, cases, out_dir, rdir, a.variant, a.timeout, transcripts)


if __name__ == "__main__":
    main()
