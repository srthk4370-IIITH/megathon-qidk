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


class AdbError(RuntimeError):
    pass


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

    TRANSIENT = ("device offline", "no devices", "not found", "closed", "protocol fault", "connection reset",
                 "unauthorized", "device still authorizing", "cannot connect", "transport")

    def run(self, *args, capture=True, timeout=None, check=True, retries=4) -> subprocess.CompletedProcess:
        """adb with retry/backoff on transport errors (board rebooting, USB hiccup, adbd restart)."""
        cmd = self._base() + list(args)
        delay = 5
        for attempt in range(retries + 1):
            try:
                r = subprocess.run(cmd, env=self.env, stdin=subprocess.DEVNULL, capture_output=capture, text=True, timeout=timeout,
                                   encoding="utf-8", errors="replace")
            except FileNotFoundError:
                raise AdbError(f"adb binary not found: {self.exe}")
            err = ((r.stderr or "") + (r.stdout or "" if capture else "")).lower()
            # for `shell`, only adb's own messages ("error: ...", "adb: ...") count, not the remote command's output
            transient = r.returncode != 0 and any(t in err for t in self.TRANSIENT) and \
                (args[0] != "shell" or "error:" in err or "adb:" in err)
            if r.returncode == 255 and args[0] == "shell":   # adb itself failed (not the remote command)
                transient = True
            if transient and attempt < retries:
                print(f"[adb] transient error ({err.strip()[:120]}); retry in {delay}s", file=sys.stderr)
                time.sleep(delay)
                self.wait_for_device(120)
                delay = min(delay * 2, 60)
                continue
            if check and r.returncode != 0:
                raise AdbError(f"adb {' '.join(a[:80] for a in args)} -> {r.returncode}\n{(r.stderr or '')[-2000:]}")
            return r
        raise AdbError("unreachable")

    def wait_for_device(self, timeout_s: int = 300) -> bool:
        try:
            subprocess.run(self._base() + ["wait-for-device"], env=self.env, stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout_s)
        except subprocess.TimeoutExpired:
            return False
        r = subprocess.run(self._base() + ["get-state"], env=self.env, capture_output=True, text=True)
        return r.stdout.strip() == "device"

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
    """Run one llm-batch stage. items: {"id","prompt","max_tokens","sampler"}. Returns id -> result.

    Robustness: up to `attempts` invocations; between attempts error rows (no "text") are removed from the
    remote OUT so iv_cli retries them; timeouts / adb drops keep partial results (pulled every attempt);
    exit 2 (retryable, e.g. NPU busy) backs off before the next attempt; exit 1 with no progress stops."""
    attempts = int(os.environ.get("IV_STAGE_ATTEMPTS", "4"))
    local_out = out_dir / f"{stage}.out.jsonl"
    ok = lambda r_: "id" in r_ and isinstance(r_.get("text"), str) and "error" not in r_  # noqa: E731
    have = {r["id"]: r for r in read_jsonl(local_out) if ok(r)}
    want = {it["id"] for it in items}
    if want <= set(have):
        print(f"[device] stage {stage}: all {len(items)} cached", file=sys.stderr)
        return have
    local_in = out_dir / f"{stage}.in.jsonl"
    write_jsonl(local_in, items)  # full list: iv_cli skips ids already in the remote OUT
    rin, rout = f"{rdir}/{stage}.in.jsonl", f"{rdir}/{stage}.out.jsonl"
    adb.push(local_in, rin)
    max_tokens = max(it["max_tokens"] for it in items)
    t0 = time.time()
    backoff = 30
    for attempt in range(1, attempts + 1):
        todo = len(want - set(have))
        print(f"[device] stage {stage}: attempt {attempt}/{attempts}, {todo} prompt(s) on device ...", file=sys.stderr)
        rc = None
        try:
            r = adb.shell(f"sh {RUN_SH} llm-batch --in {rin} --out {rout} --max-tokens {max_tokens}",
                          capture=False, timeout=timeout, check=False)
            rc = r.returncode
        except subprocess.TimeoutExpired:
            print(f"[device] stage {stage}: timeout after {timeout}s (partial results kept)", file=sys.stderr)
        except AdbError as e:
            print(f"[device] stage {stage}: adb failure: {e}", file=sys.stderr)
            adb.wait_for_device(300)
        before = len(have)
        rows = []
        try:
            if adb.exists(rout):
                adb.pull(rout, local_out)
                rows = read_jsonl(local_out)
        except AdbError as e:
            print(f"[device] stage {stage}: pull failed: {e}", file=sys.stderr)
        have.update({r_["id"]: r_ for r_ in rows if ok(r_)})
        if want <= set(have):
            break
        bad = [r_ for r_ in rows if not ok(r_)]
        if bad and attempt < attempts:
            # drop error rows remotely so the resumable CLI retries those ids
            write_jsonl(local_out, [r_ for r_ in rows if ok(r_)])
            try:
                adb.push(local_out, rout)
            except AdbError:
                pass
        progressed = len(have) > before
        if rc == 1 and not progressed:
            print(f"[device] stage {stage}: iv_cli failed (exit 1) without progress; giving up", file=sys.stderr)
            break
        if attempt < attempts:
            wait = backoff if (rc == 2 or not progressed) else 5
            print(f"[device] stage {stage}: exit={rc}, {len(have)}/{len(want)} done; retry in {wait}s", file=sys.stderr)
            time.sleep(wait)
            backoff = min(backoff * 2, 300)
    print(f"[device] stage {stage}: {len(set(have) & want)}/{len(items)} results in {time.time() - t0:.0f}s",
          file=sys.stderr)
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
    return len(preds)


def run_asr(adb: Adb, clips: list[dict], out_dir: Path, rdir: str, lang: str, timeout: int) -> tuple[dict[str, str], int]:
    """Returns ({clip_id: text}, n_wavs_found). Resumable: clips with a text in asr.jsonl are skipped;
    failed clips are retried (exit 2 / no output -> backoff) up to IV_ASR_ATTEMPTS times."""
    attempts = int(os.environ.get("IV_ASR_ATTEMPTS", "3"))
    asr_path = out_dir / "asr.jsonl"
    have = {r["id"]: r for r in read_jsonl(asr_path) if r.get("id") and isinstance(r.get("text"), str)}
    n_wavs = 0
    made_dir = False
    for c in clips:
        wav = ROOT / "eval" / "clips" / f"{c['id']}.wav"
        if not wav.exists():
            print(f"[device] missing {wav.name} (record it, then run eval/convert_clips.sh)", file=sys.stderr)
            continue
        n_wavs += 1
        if c["id"] in have:
            continue
        rwav = f"{rdir}/clips/{wav.name}"
        if not made_dir:
            adb.shell(f"mkdir -p {rdir}/clips")
            made_dir = True
        adb.push(wav, rwav)
        obj = None
        for attempt in range(1, attempts + 1):
            t0 = time.time()
            try:
                r = adb.shell(f"sh {RUN_SH} asr --wav {rwav} --lang {lang}", timeout=timeout, check=False)
                lines = [l for l in (r.stdout or "").splitlines() if l.strip().startswith("{")]
                obj = json.loads(lines[-1]) if lines else {"error": {"code": "NO_OUTPUT", "message": (r.stderr or "")[-300:]}}
                rc = r.returncode
            except subprocess.TimeoutExpired:
                obj, rc = {"error": {"code": "HOST_TIMEOUT", "message": f"{timeout}s"}}, 2
            except (json.JSONDecodeError, AdbError) as e:
                obj, rc = {"error": {"code": "HOST_ERROR", "message": str(e)[:300]}}, 2
            obj["id"], obj["exit"], obj["host_wall_ms"] = c["id"], rc, int((time.time() - t0) * 1000)
            if isinstance(obj.get("text"), str):
                break
            if attempt < attempts:
                print(f"[device] asr {c['id']}: {obj.get('error')} -> retry {attempt + 1}/{attempts} in 20s", file=sys.stderr)
                time.sleep(20)
        if isinstance(obj.get("text"), str):
            have[c["id"]] = obj
        else:
            with open(out_dir / "asr_errors.jsonl", "a", encoding="utf-8") as f:
                f.write(json.dumps(obj, ensure_ascii=False) + "\n")
        write_jsonl(asr_path, list(have.values()))
        print(f"[device] asr {c['id']}: {obj.get('text', obj.get('error'))!r}", file=sys.stderr)
    return {k: v["text"] for k, v in have.items() if v.get("text")}, n_wavs


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
    ap.add_argument("--timeout", type=int, default=6 * 3600, help="per llm-batch invocation (s)")
    ap.add_argument("--asr-timeout", type=int, default=600, help="per clip (s)")
    ap.add_argument("--dry-run", action="store_true", help="render the stage-1 prompts locally, no adb")
    a = ap.parse_args()
    cases_path = a.cases or str(ROOT / "eval" / ("clips.jsonl" if a.asr else "text_cases.jsonl"))
    cases = pl.load_cases(cases_path, a.limit, a.ids)
    out_dir = ROOT / "eval" / "results" / a.run
    out_dir.mkdir(parents=True, exist_ok=True)
    rdir = f"{REMOTE}/eval/{a.run}"
    # written first so score.py knows which case file this run belongs to
    (out_dir / "run_info.json").write_text(json.dumps({"engine": "device-iv_cli", "variant": a.variant,
                                                       "cases": os.path.relpath(cases_path, ROOT), "remote": rdir,
                                                       "asr": a.asr, "e2e": a.e2e}, indent=1), encoding="utf-8")
    if a.dry_run:
        items = [{"id": f"{c['id']}::extract", "prompt": pl.extract_prompt(c["transcript"], a.variant),
                  "max_tokens": pl.MAX_TOKENS["extract"], "sampler": "greedy"} for c in cases]
        write_jsonl(out_dir / "s1_extract.in.jsonl", items)
        print(f"rendered {len(items)} prompts -> {out_dir / 's1_extract.in.jsonl'}")
        return 0
    if a.asr and not any((ROOT / "eval" / "clips" / f"{c['id']}.wav").exists() for c in cases):
        print("[device] no eval/clips/clip_NN.wav found — nothing to do (record clips, run eval/convert_clips.sh)",
              file=sys.stderr)
        return 0
    adb = Adb()
    if not adb.wait_for_device(int(os.environ.get("IV_WAIT_DEVICE_S", "600"))):
        print("[device] FATAL: board not reachable over adb", file=sys.stderr)
        return 2
    adb.shell(f"mkdir -p {rdir}")
    if not adb.exists(RUN_SH):
        print(f"[device] FATAL: {RUN_SH} missing on the board (deploy native/ first)", file=sys.stderr)
        return 4
    info = adb.shell(f"sh {RUN_SH} info", check=False, timeout=600)
    (out_dir / "engine_info.json").write_text((info.stdout or "") + (info.stderr or ""), encoding="utf-8")
    transcripts = None
    if a.asr:
        transcripts, n_wavs = run_asr(adb, cases, out_dir, rdir, a.asr_lang, a.asr_timeout)
        print(f"[device] ASR: {len(transcripts)}/{n_wavs} clip(s) transcribed", file=sys.stderr)
        if n_wavs and not transcripts:
            return 3
        if not a.e2e:
            return 0
        cases = [c for c in cases if c["id"] in transcripts]
        if not cases:
            return 0
    n = run_text(adb, cases, out_dir, rdir, a.variant, a.timeout, transcripts)
    if cases and n == 0:
        print("[device] FATAL: no LLM results at all", file=sys.stderr)
        return 3
    if n < len(cases):
        print(f"[device] WARNING: {len(cases) - n} case(s) without results; re-run the same command to resume",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AdbError as e:
        print(f"[device] FATAL adb error: {e}", file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        sys.exit(130)
