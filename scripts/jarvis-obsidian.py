#!/usr/bin/env python3
"""
Jarvis ↔ Obsidian bridge (host-side, stdlib only).

Owns the operator's Obsidian vault as a plain Markdown directory and lets
Jarvis (alive-loop, CLI, voice commands) write/read notes safely. The k8s
memory-service ObsidianVaultWriter cannot reach the host vault (Kyverno blocks
hostPath mounts), so this host module is the real, working integration.

Config (env, never hardcode secrets):
  JARVIS_OBSIDIAN_VAULT_PATH   vault dir            (default ~/JarvisVault)
  JARVIS_OBSIDIAN_ENABLED      "true"/"false"       (default true)
  JARVIS_API_BASE              gateway base url     (default https://10.113.0.176)
  JARVIS_API_HOST              ingress Host header  (default api.jarvis.local)
  JARVIS_USER                  user id              (default owner)
  SERVICE_JWT_SECRET           (from ~/.jarvis/wake.env) — for memory indexing

Commands:
  note "Title" [--body TEXT] [--folder 03_Memory] [--tags a,b] [--type note]
  daily [--text TEXT]                 append timestamped line to today's note
  memory "TEXT" [--tags a,b] [--importance 0.7] [--index]
  conversation "Title" --body TEXT
  task "TEXT" [--due 2026-06-10]
  idea "TEXT"
  search "QUERY" [--limit 10]         local keyword search across the vault
  index [--limit 200]                 push notes to memory-service vector memory
  status
"""
import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.request
import urllib.error

VAULT = os.path.expanduser(os.environ.get("JARVIS_OBSIDIAN_VAULT_PATH", "~/JarvisVault"))
ENABLED = os.environ.get("JARVIS_OBSIDIAN_ENABLED", "true").lower() != "false"
API_BASE = os.environ.get("JARVIS_API_BASE", "https://10.113.0.176").rstrip("/")
API_HOST = os.environ.get("JARVIS_API_HOST", "api.jarvis.local")
USER = os.environ.get("JARVIS_USER", "owner")

FOLDERS = {
    "inbox": "00_Inbox",
    "daily": "01_Daily",
    "conversation": "02_Conversations",
    "memory": "03_Memory",
    "task": "04_Tasks",
    "idea": "05_Ideas",
    "system": "06_System",
}

# Never write these into the vault — secret-shaped content is redacted.
SECRET_PATTERNS = [
    re.compile(r"\bsk-[A-Za-z0-9]{16,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bghp_[A-Za-z0-9]{20,}\b"),
    re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._\-]{8,}"),                 # "Bearer <token>"
    re.compile(r"(?i)\b(api[_-]?key|secret|password|passwd|token|access[_-]?token)\b\s*[:=]\s*\S{6,}"),
    re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{6,}\b"),  # JWT
]


def _now():
    return _dt.datetime.now()


def redact_secrets(text):
    if not text:
        return text, False
    redacted = text
    hit = False
    for pat in SECRET_PATTERNS:
        if pat.search(redacted):
            redacted = pat.sub("[REDACTED]", redacted)
            hit = True
    return redacted, hit


def safe_slug(title, maxlen=80):
    s = re.sub(r"[^\w\s\-а-яёА-ЯЁ]", "", title or "", flags=re.UNICODE).strip()
    s = re.sub(r"\s+", "-", s)[:maxlen].strip("-")
    return s or "note"


def vault_path(*parts):
    """Resolve a path INSIDE the vault; refuse traversal escapes."""
    root = os.path.realpath(VAULT)
    target = os.path.realpath(os.path.join(root, *parts))
    if target != root and not target.startswith(root + os.sep):
        raise ValueError("path escapes vault: %r" % (parts,))
    return target


def ensure_vault():
    os.makedirs(VAULT, exist_ok=True)
    for sub in FOLDERS.values():
        os.makedirs(os.path.join(VAULT, sub), exist_ok=True)


def atomic_write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    d = os.path.dirname(path)
    fd, tmp = tempfile.mkstemp(dir=d, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def frontmatter(meta):
    lines = ["---"]
    for k, v in meta.items():
        if isinstance(v, list):
            lines.append("%s: [%s]" % (k, ", ".join(str(x) for x in v)))
        else:
            lines.append("%s: %s" % (k, v))
    lines.append("---")
    return "\n".join(lines)


def note_id(text):
    return hashlib.sha1((text + _now().isoformat()).encode("utf-8")).hexdigest()[:12]


def make_note(title, body, folder_key="memory", note_type="note", tags=None, extra_meta=None):
    if not ENABLED:
        print("obsidian disabled (JARVIS_OBSIDIAN_ENABLED=false)", file=sys.stderr)
        return None
    ensure_vault()
    title, t_hit = redact_secrets(title)
    body, b_hit = redact_secrets(body or "")
    tags = tags or []
    base_tags = ["jarvis", note_type]
    all_tags = base_tags + [t for t in tags if t not in base_tags]
    nid = note_id(title + body)
    folder = FOLDERS.get(folder_key, FOLDERS["memory"])
    fname = "%s-%s.md" % (_now().strftime("%Y%m%d-%H%M%S"), safe_slug(title))
    path = vault_path(folder, fname)
    meta = {
        "id": nid,
        "title": title,
        "type": note_type,
        "created": _now().isoformat(timespec="seconds"),
        "source": "jarvis",
        "tags": ["#" + t for t in all_tags],
    }
    if extra_meta:
        meta.update(extra_meta)
    content = "%s\n\n# %s\n\n%s\n" % (frontmatter(meta), title, body)
    atomic_write(path, content)
    rel = os.path.relpath(path, VAULT)
    if t_hit or b_hit:
        print("⚠ secret-shaped content redacted before write", file=sys.stderr)
    print(json.dumps({"ok": True, "id": nid, "path": rel, "vault": VAULT}, ensure_ascii=False))
    return path


def append_daily(text):
    if not ENABLED:
        return None
    ensure_vault()
    text, _ = redact_secrets(text or "")
    day = _now().strftime("%Y-%m-%d")
    path = vault_path(FOLDERS["daily"], "%s.md" % day)
    if not os.path.exists(path):
        meta = {
            "id": note_id(day),
            "title": "Daily — %s" % day,
            "type": "daily",
            "created": _now().isoformat(timespec="seconds"),
            "source": "jarvis",
            "tags": ["#jarvis", "#daily"],
        }
        header = "%s\n\n# Daily — %s\n\n" % (frontmatter(meta), day)
        atomic_write(path, header)
    stamp = _now().strftime("%H:%M")
    with open(path, "a", encoding="utf-8") as f:
        f.write("- **%s** — %s\n" % (stamp, text))
    rel = os.path.relpath(path, VAULT)
    print(json.dumps({"ok": True, "path": rel, "appended": text[:80]}, ensure_ascii=False))
    return path


def search_vault(query, limit=10):
    ensure_vault()
    q = (query or "").lower().strip()
    if not q:
        print(json.dumps({"ok": False, "error": "empty query"}))
        return
    terms = [t for t in re.split(r"\s+", q) if t]
    hits = []
    for dirpath, _dirs, files in os.walk(VAULT):
        for fn in files:
            if not fn.endswith(".md"):
                continue
            fp = os.path.join(dirpath, fn)
            try:
                with open(fp, encoding="utf-8", errors="ignore") as f:
                    text = f.read()
            except OSError:
                continue
            low = text.lower()
            score = sum(low.count(t) for t in terms)
            if score > 0:
                idx = min((low.find(t) for t in terms if low.find(t) >= 0), default=0)
                snippet = text[max(0, idx - 40): idx + 120].replace("\n", " ")
                hits.append({"path": os.path.relpath(fp, VAULT), "score": score, "snippet": snippet.strip()})
    hits.sort(key=lambda h: h["score"], reverse=True)
    print(json.dumps({"ok": True, "query": query, "hits": hits[:limit]}, ensure_ascii=False, indent=2))


def _service_token():
    secret = os.environ.get("SERVICE_JWT_SECRET")
    wake = os.path.expanduser("~/.jarvis/wake.env")
    if not secret and os.path.exists(wake):
        for line in open(wake, encoding="utf-8"):
            if line.startswith("SERVICE_JWT_SECRET="):
                secret = line.split("=", 1)[1].strip()
                break
    if not secret:
        return None
    helper = os.path.expanduser("~/Jarvis/Jarvis2.0/scripts/runtime/make_service_jwt.py")
    if not os.path.exists(helper):
        return None
    import subprocess
    try:
        out = subprocess.check_output(
            [sys.executable, helper, "--secret", secret, "--service", "obsidian",
             "--subject", "obsidian", "--ttl-seconds", "300"],
            stderr=subprocess.DEVNULL, timeout=15)
        return out.decode().strip()
    except Exception:
        return None


def index_to_memory(limit=200):
    """Push vault notes into memory-service vector memory (best-effort)."""
    ensure_vault()
    token = _service_token()
    if not token:
        print(json.dumps({"ok": False, "error": "no SERVICE_JWT_SECRET — local-only mode; notes still searchable via 'search'"}))
        return
    notes = []
    for dirpath, _d, files in os.walk(VAULT):
        for fn in files:
            if fn.endswith(".md") and fn != "README.md":
                notes.append(os.path.join(dirpath, fn))
    notes = notes[:limit]
    ok = fail = 0
    import ssl
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    for fp in notes:
        try:
            with open(fp, encoding="utf-8", errors="ignore") as f:
                body = f.read()
        except OSError:
            continue
        payload = json.dumps({
            "category": "PROJECTS",
            "title": os.path.basename(fp)[:120], "body": body[:8000],
            "source": "obsidian:" + os.path.relpath(fp, VAULT),
            "tags": ["obsidian"],
        }).encode("utf-8")
        req = urllib.request.Request(
            API_BASE + "/api/v1/memory/notes", data=payload, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Host", API_HOST)
        req.add_header("X-Service-Token", token)
        req.add_header("X-User-Id", USER)
        try:
            urllib.request.urlopen(req, timeout=10, context=ctx)
            ok += 1
        except Exception:
            fail += 1
    print(json.dumps({"ok": True, "indexed": ok, "failed": fail, "total": len(notes),
                      "note": "if failed>0, memory ingest route may be unavailable; local search still works"}))


def status():
    exists = os.path.isdir(VAULT)
    counts = {}
    total = 0
    if exists:
        for key, sub in FOLDERS.items():
            d = os.path.join(VAULT, sub)
            n = len([f for f in os.listdir(d) if f.endswith(".md")]) if os.path.isdir(d) else 0
            counts[sub] = n
            total += n
    print(json.dumps({
        "ok": True, "enabled": ENABLED, "vault": VAULT, "exists": exists,
        "total_notes": total, "by_folder": counts,
        "memory_indexing": "available" if _service_token() else "local-only (no SERVICE_JWT_SECRET)",
    }, ensure_ascii=False, indent=2))


def main():
    p = argparse.ArgumentParser(prog="jarvis-obsidian")
    sub = p.add_subparsers(dest="cmd", required=True)

    n = sub.add_parser("note")
    n.add_argument("title")
    n.add_argument("--body", default="")
    n.add_argument("--folder", default="memory")
    n.add_argument("--type", default="note")
    n.add_argument("--tags", default="")

    d = sub.add_parser("daily")
    d.add_argument("--text", default="")

    m = sub.add_parser("memory")
    m.add_argument("text")
    m.add_argument("--tags", default="")
    m.add_argument("--importance", default="0.5")
    m.add_argument("--index", action="store_true")

    c = sub.add_parser("conversation")
    c.add_argument("title")
    c.add_argument("--body", default="")

    t = sub.add_parser("task")
    t.add_argument("text")
    t.add_argument("--due", default="")

    i = sub.add_parser("idea")
    i.add_argument("text")

    s = sub.add_parser("search")
    s.add_argument("query")
    s.add_argument("--limit", type=int, default=10)

    ix = sub.add_parser("index")
    ix.add_argument("--limit", type=int, default=200)

    sub.add_parser("status")

    a = p.parse_args()
    tags = [x.strip() for x in getattr(a, "tags", "").split(",") if x.strip()] if hasattr(a, "tags") else []

    if a.cmd == "note":
        make_note(a.title, a.body, folder_key=a.folder, note_type=a.type, tags=tags)
    elif a.cmd == "daily":
        append_daily(a.text or (sys.stdin.read() if not sys.stdin.isatty() else ""))
    elif a.cmd == "memory":
        make_note(a.text[:60] + ("…" if len(a.text) > 60 else ""), a.text, folder_key="memory",
                  note_type="memory", tags=tags, extra_meta={"importance": a.importance})
        if a.index:
            index_to_memory()
    elif a.cmd == "conversation":
        make_note(a.title, a.body, folder_key="conversation", note_type="conversation", tags=tags)
    elif a.cmd == "task":
        extra = {"due": a.due} if a.due else None
        make_note(a.text[:60], "- [ ] " + a.text + (("\nDue: " + a.due) if a.due else ""),
                  folder_key="task", note_type="task", extra_meta=extra)
    elif a.cmd == "idea":
        make_note(a.text[:60], a.text, folder_key="idea", note_type="idea")
    elif a.cmd == "search":
        search_vault(a.query, a.limit)
    elif a.cmd == "index":
        index_to_memory(a.limit)
    elif a.cmd == "status":
        status()


if __name__ == "__main__":
    main()
