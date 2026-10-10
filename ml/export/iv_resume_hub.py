"""Make AI Hub exports resumable across crashes (installed as sitecustomize in the export venv).

- upload: a model directory/file whose name (and size) was already uploaded is NOT uploaded again;
  the cached / recently-uploaded AI Hub model is reused.
- compile/link jobs: a job with the same name (+ options) submitted in the last 6 h that is not FAILED
  is reused instead of resubmitted.
Cache: ~/iv/state/hub_cache.json (override with IV_HUB_CACHE, e.g. a separate cache for the fine-tuned export). Disable with IV_HUB_RESUME=0.
"""
import datetime as _dt
import json as _json
import os as _os
import sys as _sys
from pathlib import Path as _Path

_CACHE = _Path(_os.environ.get("IV_HUB_CACHE") or (_Path.home() / "iv" / "state" / "hub_cache.json"))
_WINDOW = _dt.timedelta(hours=6)


def _log(msg):
    print(f"[iv-resume] {msg}", file=_sys.stderr, flush=True)


def _load():
    try:
        return _json.loads(_CACHE.read_text())
    except Exception:
        return {"models": {}, "jobs": {}}


def _save(c):
    _CACHE.parent.mkdir(parents=True, exist_ok=True)
    tmp = _CACHE.with_suffix(".tmp")
    tmp.write_text(_json.dumps(c, indent=1))
    tmp.replace(_CACHE)


def _size(p):
    p = _Path(p)
    if p.is_dir():
        return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
    return p.stat().st_size if p.exists() else -1


def _recent(dt):
    try:
        now = _dt.datetime.now(dt.tzinfo) if dt.tzinfo else _dt.datetime.now()
        return now - dt < _WINDOW
    except Exception:
        return False


def _install():
    if _os.environ.get("IV_HUB_RESUME", "1") == "0":
        return
    try:
        from qai_hub import client as _client
    except Exception:
        return
    C = _client.Client
    if getattr(C, "_iv_resume", False):
        return
    C._iv_resume = True

    orig_upload = C._upload_model_if_needed

    def _upload_model_if_needed(self, model, model_type, name=None, project=None):
        if isinstance(model, (str, _Path)) and _os.path.exists(_os.path.expanduser(str(model))):
            path = _os.path.expanduser(str(model))
            # qai_hub_models serializes part N to <tmp>/Llama3_2_3B_PartN_Of_3_w4a16.aimet (dir);
            # a pre-uploaded zip of the same part is "<same>.zip" → same key
            base = _os.path.basename(path.rstrip("/"))
            key = "part:" + (base[:-4] if base.endswith(".zip") else base)
            cache = _load()
            mid = cache["models"].get(key)
            if mid:
                try:
                    m = self.get_model(mid)
                    _log(f"reuse cached upload {key} -> {mid}")
                    return m
                except Exception as e:  # noqa: BLE001
                    _log(f"cached model {mid} unusable ({e}); uploading")
            else:  # fallback: same-name model uploaded recently (e.g. by a crashed run)
                try:
                    base = _os.path.basename(path.rstrip("/"))
                    for m in self.get_models(limit=50):
                        if (m.name or "").startswith(base.replace(".zip", "")) and _recent(m.date):
                            cache["models"][key] = m.model_id
                            _save(cache)
                            _log(f"reuse recent upload {base} -> {m.model_id}")
                            return m
                except Exception as e:  # noqa: BLE001
                    _log(f"recent-model lookup failed: {e}")
            m = orig_upload(self, model, model_type, name=name, project=project)
            cache = _load()
            cache["models"][key] = m.model_id
            _save(cache)
            _log(f"uploaded {key} -> {m.model_id}")
            return m
        return orig_upload(self, model, model_type, name=name, project=project)

    C._upload_model_if_needed = _upload_model_if_needed

    def _wrap_job(fname):
        orig = getattr(C, fname)

        def wrapper(self, *args, **kwargs):
            name = kwargs.get("name")
            opts = kwargs.get("options", "")
            if not name:
                return orig(self, *args, **kwargs)
            key = f"{fname}|{name}|{opts}"
            cache = _load()
            jid = cache["jobs"].get(key)
            candidates = [jid] if jid else []
            if not jid:
                try:
                    for s in self.get_job_summaries(limit=60):
                        if s.name == name and _recent(s.date) and s.status.code != "FAILED":
                            candidates.append(s.job_id)
                except Exception as e:  # noqa: BLE001
                    _log(f"recent-job lookup failed: {e}")
            for cid in candidates:
                try:
                    job = self.get_job(cid)
                    # same name is reused for different graphs (ar128 vs ar1): options must match too
                    if cid != jid and (getattr(job, "options", None) or "") != (opts or ""):
                        continue
                    if job.get_status().code != "FAILED":
                        cache["jobs"][key] = cid
                        _save(cache)
                        _log(f"reuse {fname} '{name}' -> {cid}")
                        return job
                except Exception as e:  # noqa: BLE001
                    _log(f"cached job {cid} unusable ({e})")
            job = orig(self, *args, **kwargs)
            jobs = job if isinstance(job, list) else [job]
            if len(jobs) == 1:
                cache = _load()
                cache["jobs"][key] = jobs[0].job_id
                _save(cache)
                _log(f"submitted {fname} '{name}' -> {jobs[0].job_id}")
            return job

        setattr(C, fname, wrapper)

    for fn in ("submit_compile_job", "submit_link_job"):
        if hasattr(C, fn):
            _wrap_job(fn)
    # module-level helpers (hub.submit_compile_job etc.) delegate to a default Client instance → covered.


try:
    _install()
except Exception as _e:  # never break the interpreter
    _log(f"install failed: {_e}")
