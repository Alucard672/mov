#!/usr/bin/env python3
"""
每日更新内容登记 API + 静态页（daily.alucard.top）：
  短路径：/  /health  /preview  /register  /list  /day
  兼容旧路径：/app/daily/*
"""
from __future__ import annotations

from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from datetime import datetime, timezone, timedelta
from pathlib import Path
import json
import os
import re
import threading
import urllib.request
import urllib.error

PORT = int(os.environ.get("DAILY_PORT", "3607"))
FILM_INDEX_URL = os.environ.get(
    "FILM_INDEX_URL", "https://api.alucard.top/index"
).strip()
TOKEN = os.environ.get("DAILY_TOKEN", "alucard")

# 数据目录：优先服务器路径，否则本地 server/data/daily
_DEFAULT_SERVER_DATA = "/opt/film/data/daily"
_LOCAL_DATA = str(Path(__file__).resolve().parent / "data" / "daily")
DATA = os.environ.get(
    "DAILY_DATA",
    _DEFAULT_SERVER_DATA if os.path.isdir("/opt/film/data") else _LOCAL_DATA,
)
os.makedirs(DATA, exist_ok=True)

STATIC_DIR = Path(__file__).resolve().parent / "static"
TZ = timezone(timedelta(hours=8))
_lock = threading.Lock()

# 关心的分类（与 App 首页一致）
FOCUS_CATEGORIES = ("电影", "电视剧", "综艺", "动漫")


def _now() -> datetime:
    return datetime.now(TZ)


def _today() -> str:
    return _now().strftime("%Y-%m-%d")


def _day_path(day: str) -> str:
    return os.path.join(DATA, f"{day}.json")


def _load_json(path: str, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _save_json(path: str, obj) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def _json(handler: BaseHTTPRequestHandler, code: int, obj) -> None:
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.send_header(
        "Access-Control-Allow-Headers", "Content-Type, Authorization, X-Daily-Token"
    )
    handler.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _read_body(handler: BaseHTTPRequestHandler) -> bytes:
    n = int(handler.headers.get("Content-Length") or "0")
    if n <= 0:
        return b""
    return handler.rfile.read(min(n, 2_000_000))


def _token_ok(handler: BaseHTTPRequestHandler, qs: dict) -> bool:
    if not TOKEN:
        return True
    t = (qs.get("token") or [""])[0]
    if t == TOKEN:
        return True
    auth = handler.headers.get("X-Daily-Token") or handler.headers.get("Authorization") or ""
    auth = auth.replace("Bearer", "").strip()
    return auth == TOKEN


def _valid_day(day: str) -> bool:
    return bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", day or ""))


def _film_id(item: dict) -> str:
    for k in ("id", "mid", "ID", "Mid"):
        v = item.get(k)
        if v is not None and str(v).strip() != "":
            return str(v)
    return ""


def _film_card(item: dict) -> dict:
    if not isinstance(item, dict):
        return {}
    name = str(item.get("name") or "").strip()
    if not name:
        return {}
    return {
        "id": _film_id(item),
        "name": name,
        "remarks": str(item.get("remarks") or "").strip(),
        "picture": str(item.get("picture") or item.get("pic") or "").strip(),
        "score": str(item.get("score") or item.get("dbScore") or "").strip(),
        "year": str(item.get("year") or "").strip(),
    }


def _parse_index(payload: dict) -> dict:
    """从 /api/index 响应解析各分类热播/最新。"""
    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, dict):
        data = payload if isinstance(payload, dict) else {}

    sections_out = []
    content = data.get("content") or []
    if not isinstance(content, list):
        content = []

    for sec in content:
        if not isinstance(sec, dict):
            continue
        nav = sec.get("nav") or {}
        if isinstance(nav, list) and nav:
            nav = nav[0] if isinstance(nav[0], dict) else {}
        if not isinstance(nav, dict):
            nav = {}
        cat = str(nav.get("name") or nav.get("title") or "未分类").strip() or "未分类"
        hot = [_film_card(x) for x in (sec.get("hot") or []) if isinstance(x, dict)]
        movies = [_film_card(x) for x in (sec.get("movies") or []) if isinstance(x, dict)]
        hot = [x for x in hot if x.get("name")]
        movies = [x for x in movies if x.get("name")]
        if not hot and not movies:
            continue
        sections_out.append(
            {
                "category": cat,
                "hot": hot,
                "latest": movies,  # App 里 movies = 「最新」
                "hotCount": len(hot),
                "latestCount": len(movies),
            }
        )

    banners = []
    for b in data.get("banners") or []:
        if not isinstance(b, dict):
            continue
        card = _film_card(b)
        if card.get("name"):
            banners.append(card)

    # 统计：今日相关（备注含「更新」或日期串）
    today_tag = _now().strftime("%Y%m%d")
    updating = []
    for sec in sections_out:
        for f in sec.get("latest") or []:
            r = f.get("remarks") or ""
            if "更新" in r or today_tag in r:
                updating.append(
                    {
                        "category": sec["category"],
                        "name": f["name"],
                        "remarks": r,
                        "id": f.get("id", ""),
                    }
                )

    return {
        "banners": banners,
        "sections": sections_out,
        "stats": {
            "sectionCount": len(sections_out),
            "bannerCount": len(banners),
            "latestTotal": sum(s["latestCount"] for s in sections_out),
            "hotTotal": sum(s["hotCount"] for s in sections_out),
            "updatingCount": len(updating),
        },
        "updating": updating,
        "focusCategories": list(FOCUS_CATEGORIES),
    }


def fetch_index(url: str | None = None) -> dict:
    target = (url or FILM_INDEX_URL).strip()
    req = urllib.request.Request(
        target,
        headers={
            "User-Agent": "AlucardDailyRegister/1.0",
            "Accept": "application/json",
        },
        method="GET",
    )
    with urllib.request.urlopen(req, timeout=25) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        raise ValueError("index 响应不是 JSON 对象")
    return payload


def build_preview(source_url: str | None = None) -> dict:
    payload = fetch_index(source_url)
    parsed = _parse_index(payload)
    return {
        "ok": True,
        "day": _today(),
        "fetchedAt": _now().isoformat(),
        "source": source_url or FILM_INDEX_URL,
        **parsed,
        "registered": os.path.isfile(_day_path(_today())),
    }


def register_day(
    note: str = "",
    force: bool = False,
    source_url: str | None = None,
    manual: dict | None = None,
) -> dict:
    day = _today()
    path = _day_path(day)
    with _lock:
        if os.path.isfile(path) and not force:
            existing = _load_json(path, {})
            return {
                "ok": False,
                "error": "today_already_registered",
                "message": f"{day} 已登记，如需覆盖请 force=true",
                "record": existing,
            }

        if manual and isinstance(manual, dict) and manual.get("sections"):
            parsed = {
                "banners": manual.get("banners") or [],
                "sections": manual.get("sections") or [],
                "stats": manual.get("stats") or {},
                "updating": manual.get("updating") or [],
                "focusCategories": list(FOCUS_CATEGORIES),
            }
            source = "manual"
        else:
            payload = fetch_index(source_url)
            parsed = _parse_index(payload)
            source = source_url or FILM_INDEX_URL

        prev_note = ""
        if os.path.isfile(path):
            old = _load_json(path, {})
            if isinstance(old, dict):
                prev_note = str(old.get("note") or "")

        record = {
            "day": day,
            "registeredAt": _now().isoformat(),
            "updatedAt": _now().isoformat(),
            "source": source,
            "note": (note or prev_note or "").strip()[:2000],
            "banners": parsed.get("banners") or [],
            "sections": parsed.get("sections") or [],
            "stats": parsed.get("stats") or {},
            "updating": parsed.get("updating") or [],
        }
        _save_json(path, record)
        return {"ok": True, "created": True, "record": record}


def list_days() -> dict:
    days = []
    with _lock:
        for name in sorted(os.listdir(DATA), reverse=True):
            if not name.endswith(".json"):
                continue
            day = name[:-5]
            if not _valid_day(day):
                continue
            rec = _load_json(os.path.join(DATA, name), {})
            stats = rec.get("stats") if isinstance(rec, dict) else {}
            days.append(
                {
                    "day": day,
                    "registeredAt": rec.get("registeredAt") if isinstance(rec, dict) else "",
                    "note": (rec.get("note") or "")[:120] if isinstance(rec, dict) else "",
                    "latestTotal": (stats or {}).get("latestTotal", 0),
                    "updatingCount": (stats or {}).get("updatingCount", 0),
                    "sectionCount": (stats or {}).get("sectionCount", 0),
                }
            )
    return {"ok": True, "count": len(days), "days": days}


def get_day(day: str) -> dict:
    if not _valid_day(day):
        return {"ok": False, "error": "invalid date"}
    path = _day_path(day)
    with _lock:
        if not os.path.isfile(path):
            return {"ok": False, "error": "not_found", "day": day}
        rec = _load_json(path, None)
    if not isinstance(rec, dict):
        return {"ok": False, "error": "corrupt", "day": day}
    return {"ok": True, "record": rec}


def update_day(day: str, payload: dict) -> dict:
    if not _valid_day(day):
        return {"ok": False, "error": "invalid date"}
    path = _day_path(day)
    with _lock:
        if not os.path.isfile(path):
            return {"ok": False, "error": "not_found", "day": day}
        rec = _load_json(path, {})
        if not isinstance(rec, dict):
            rec = {"day": day}
        if "note" in payload:
            rec["note"] = str(payload.get("note") or "").strip()[:2000]
        if isinstance(payload.get("manualItems"), list):
            # 手工追加条目：[{category,name,remarks,kind}]
            items = []
            for it in payload["manualItems"]:
                if not isinstance(it, dict):
                    continue
                name = str(it.get("name") or "").strip()
                if not name:
                    continue
                items.append(
                    {
                        "category": str(it.get("category") or "其他").strip()[:40],
                        "name": name[:120],
                        "remarks": str(it.get("remarks") or "").strip()[:80],
                        "kind": str(it.get("kind") or "latest").strip()[:20],
                        "id": str(it.get("id") or "").strip()[:40],
                    }
                )
            rec["manualItems"] = items
        rec["updatedAt"] = _now().isoformat()
        _save_json(path, rec)
        return {"ok": True, "record": rec}


def delete_day(day: str) -> dict:
    if not _valid_day(day):
        return {"ok": False, "error": "invalid date"}
    path = _day_path(day)
    with _lock:
        if not os.path.isfile(path):
            return {"ok": False, "error": "not_found"}
        os.remove(path)
    return {"ok": True, "deleted": day}


def _serve_html(handler: BaseHTTPRequestHandler, path: Path) -> None:
    if not path.is_file():
        _json(handler, 404, {"ok": False, "error": "page not found"})
        return
    body = path.read_bytes()
    handler.send_response(200)
    handler.send_header("Content-Type", "text/html; charset=utf-8")
    handler.send_header("Cache-Control", "no-cache")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[daily]", fmt % args, flush=True)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header(
            "Access-Control-Allow-Headers", "Content-Type, Authorization, X-Daily-Token"
        )
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.end_headers()

    def do_GET(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        qs = parse_qs(u.query)

        # 静态页
        if path in ("/app/daily", "/app/daily/", "/daily", "/"):
            _serve_html(self, STATIC_DIR / "daily.html")
            return
        if path.endswith("/static/daily.html") or path.endswith("daily.html"):
            _serve_html(self, STATIC_DIR / "daily.html")
            return

        if path.endswith("/health") or path == "/app/daily/health":
            _json(
                self,
                200,
                {
                    "ok": True,
                    "service": "daily-update",
                    "version": "1",
                    "dataDir": DATA,
                    "source": FILM_INDEX_URL,
                    "today": _today(),
                },
            )
            return

        if path.endswith("/preview") or path == "/app/daily/preview":
            if not _token_ok(self, qs):
                _json(self, 401, {"ok": False, "error": "unauthorized"})
                return
            try:
                src = (qs.get("url") or [None])[0]
                _json(self, 200, build_preview(src))
            except urllib.error.HTTPError as e:
                _json(self, 502, {"ok": False, "error": f"upstream HTTP {e.code}"})
            except Exception as e:
                _json(self, 502, {"ok": False, "error": str(e)})
            return

        if path.endswith("/list") or path == "/app/daily/list":
            if not _token_ok(self, qs):
                _json(self, 401, {"ok": False, "error": "unauthorized"})
                return
            _json(self, 200, list_days())
            return

        if path.endswith("/day") or path == "/app/daily/day":
            if not _token_ok(self, qs):
                _json(self, 401, {"ok": False, "error": "unauthorized"})
                return
            day = (qs.get("date") or [_today()])[0]
            result = get_day(day)
            code = 200 if result.get("ok") else (404 if result.get("error") == "not_found" else 400)
            _json(self, code, result)
            return

        _json(self, 404, {"ok": False, "error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        qs = parse_qs(u.query)

        if path.endswith("/register") or path == "/app/daily/register":
            if not _token_ok(self, qs):
                _json(self, 401, {"ok": False, "error": "unauthorized"})
                return
            raw = _read_body(self)
            try:
                payload = json.loads(raw.decode("utf-8") or "{}") if raw else {}
            except Exception:
                _json(self, 400, {"ok": False, "error": "invalid json"})
                return
            if not isinstance(payload, dict):
                payload = {}
            note = str(payload.get("note") or "")
            force = bool(payload.get("force"))
            src = payload.get("url") or (qs.get("url") or [None])[0]
            manual = payload.get("manual")
            try:
                result = register_day(note=note, force=force, source_url=src, manual=manual)
                code = 200 if result.get("ok") else 409
                _json(self, code, result)
            except urllib.error.HTTPError as e:
                _json(self, 502, {"ok": False, "error": f"upstream HTTP {e.code}"})
            except Exception as e:
                _json(self, 502, {"ok": False, "error": str(e)})
            return

        _json(self, 404, {"ok": False, "error": "not found"})

    def do_PUT(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        qs = parse_qs(u.query)
        if path.endswith("/day") or path == "/app/daily/day":
            if not _token_ok(self, qs):
                _json(self, 401, {"ok": False, "error": "unauthorized"})
                return
            day = (qs.get("date") or [""])[0]
            raw = _read_body(self)
            try:
                payload = json.loads(raw.decode("utf-8") or "{}")
            except Exception:
                _json(self, 400, {"ok": False, "error": "invalid json"})
                return
            if not isinstance(payload, dict):
                _json(self, 400, {"ok": False, "error": "invalid body"})
                return
            result = update_day(day, payload)
            code = 200 if result.get("ok") else (404 if result.get("error") == "not_found" else 400)
            _json(self, code, result)
            return
        _json(self, 404, {"ok": False, "error": "not found"})

    def do_DELETE(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        qs = parse_qs(u.query)
        if path.endswith("/day") or path == "/app/daily/day":
            if not _token_ok(self, qs):
                _json(self, 401, {"ok": False, "error": "unauthorized"})
                return
            day = (qs.get("date") or [""])[0]
            result = delete_day(day)
            code = 200 if result.get("ok") else (404 if result.get("error") == "not_found" else 400)
            _json(self, code, result)
            return
        _json(self, 404, {"ok": False, "error": "not found"})


def main():
    print(f"[daily] data={DATA}", flush=True)
    print(f"[daily] source={FILM_INDEX_URL}", flush=True)
    print(f"[daily] listen=0.0.0.0:{PORT}", flush=True)
    print(f"[daily] page=http://127.0.0.1:{PORT}/", flush=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[daily] stop", flush=True)


if __name__ == "__main__":
    main()
