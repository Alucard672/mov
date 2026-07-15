#!/usr/bin/env python3
"""
轻量解析 + 下载 API：
  GET  /app/ytdlp/health
  POST /app/ytdlp/resolve   {"url":"..."}  支持：直链 / 抖音分享文案 / yt-dlp
  POST /app/ytdlp/download  {"url":"...","mode":"resolve|server"}
  GET  /app/ytdlp/file/{id}/{name}
"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, unquote, quote
from urllib.request import Request, urlopen, build_opener, HTTPRedirectHandler
import json
import os
import re
import subprocess
import hashlib
import shutil
import ssl

PORT = 3605
YTDLP = "/opt/film/venv-ytdlp/bin/yt-dlp"
DATA = "/opt/film/data/ytdlp"
os.makedirs(DATA, exist_ok=True)

UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
)
CTX = ssl.create_default_context()


def _json(handler, code, obj):
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def safe_id(url: str) -> str:
    return hashlib.sha1(url.encode("utf-8", "ignore")).hexdigest()[:16]


def extract_first_url(text: str) -> str:
    """从抖音分享文案里抠出 http(s) 链接。"""
    text = (text or "").strip()
    if not text:
        return ""
    # 优先抖音短链 / 完整链
    patterns = [
        r"https?://v\.douyin\.com/[A-Za-z0-9_\-]+/?",
        r"https?://www\.douyin\.com/video/\d+[^\s]*",
        r"https?://www\.iesdouyin\.com/share/video/\d+[^\s]*",
        r"https?://www\.tiktok\.com/[^\s]+",
        r"https?://vm\.tiktok\.com/[A-Za-z0-9]+/?",
        r"https?://[^\s\"'<>]+",
    ]
    for pat in patterns:
        m = re.search(pat, text)
        if m:
            u = m.group(0).rstrip(".,;，。、）)]}'\"")
            # 去掉粘贴时夹在中间的非法空白
            u = re.sub(r"\s+", "", u)
            return u
    return text if re.match(r"^https?://", text, re.I) else ""


def http_get(url: str, timeout: int = 20) -> tuple[str, str]:
    """返回 (final_url, body_text)。"""
    req = Request(url, headers={"User-Agent": UA, "Referer": "https://www.douyin.com/"})
    with urlopen(req, timeout=timeout, context=CTX) as r:
        final = r.geturl()
        body = r.read().decode("utf-8", "ignore")
        return final, body


def resolve_douyin(url: str) -> dict | None:
    """解析抖音分享页 _ROUTER_DATA / play_addr。"""
    if not re.search(r"douyin\.com|iesdouyin\.com", url, re.I):
        return None
    try:
        final, html = http_get(url, timeout=20)
    except Exception as e:
        return {"ok": False, "error": f"打开抖音链接失败: {e}"}

    # 解析 JSON
    m = re.search(r"window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*;?\s*</script>", html, re.S)
    item = None
    title = "douyin_video"
    if m:
        try:
            data = json.loads(m.group(1))
            page = (data.get("loaderData") or {}).get("video_(id)/page") or {}
            items = ((page.get("videoInfoRes") or {}).get("item_list")) or []
            if items:
                item = items[0]
                title = (item.get("desc") or title).strip() or title
        except Exception as e:
            print(f"[douyin] json err {e}", flush=True)

    play_urls = []
    if item:
        video = item.get("video") or {}
        for key in ("play_addr", "download_addr", "play_addr_h264"):
            pa = video.get(key) or {}
            for u in pa.get("url_list") or []:
                if isinstance(u, str) and u.startswith("http"):
                    play_urls.append(u)
        br = video.get("bit_rate") or []
        if isinstance(br, list):
            for b in br:
                pa = (b.get("play_addr") or {}).get("url_list") or []
                for u in pa:
                    if isinstance(u, str) and u.startswith("http"):
                        play_urls.append(u)

    if not play_urls:
        # 兜底正则
        html2 = html.replace("\\u002F", "/").replace("\\/", "/")
        play_urls = re.findall(
            r"https://aweme\.snssdk\.com/aweme/v1/playwm/\?[^\"'\\s]+", html2
        )

    if not play_urls:
        return {"ok": False, "error": "抖音页面未解析到视频地址（可能已失效）"}

    # 优先无水印 play
    preferred = []
    for u in play_urls:
        preferred.append(u.replace("/playwm/", "/play/").replace("playwm", "play"))
        preferred.append(u)

    # 去重保持顺序
    seen = set()
    ordered = []
    for u in preferred:
        if u not in seen:
            seen.add(u)
            ordered.append(u)

    # 验证哪个能下到 mp4
    for u in ordered:
        try:
            req = Request(
                u,
                headers={
                    "User-Agent": UA,
                    "Referer": "https://www.douyin.com/",
                },
            )
            with urlopen(req, timeout=15, context=CTX) as r:
                ct = (r.headers.get("Content-Type") or "").lower()
                cl = int(r.headers.get("Content-Length") or "0")
                head = r.read(16)
                ok = ("video" in ct or head[4:8] == b"ftyp" or cl > 10000)
                if ok:
                    no_wm = "/play/?" in u or "playwm" not in u
                    return {
                        "ok": True,
                        "title": re.sub(r"\s+", " ", title)[:80],
                        "ext": "mp4",
                        "url": r.geturl() if r.geturl().startswith("http") else u,
                        "webpage_url": final,
                        "extractor": "douyin-share",
                        "watermark": not no_wm,
                    }
        except Exception as e:
            print(f"[douyin] probe fail {e}", flush=True)
            continue

    # 验证全失败仍返回第一个（客户端再试）
    u0 = ordered[0]
    return {
        "ok": True,
        "title": re.sub(r"\s+", " ", title)[:80],
        "ext": "mp4",
        "url": u0.replace("/playwm/", "/play/"),
        "webpage_url": final,
        "extractor": "douyin-share",
        "watermark": False,
    }


def resolve_url(url: str) -> dict:
    url = extract_first_url(url)
    if not url:
        return {"ok": False, "error": "未找到有效链接，请粘贴含 https:// 的完整分享内容"}

    # 1) 抖音专用
    if re.search(r"douyin\.com|iesdouyin\.com", url, re.I):
        dy = resolve_douyin(url)
        if dy is not None:
            return dy

    # 2) yt-dlp 通用
    cmd = [
        YTDLP,
        "--no-playlist",
        "--no-warnings",
        "-J",
        "--user-agent",
        UA,
        url,
    ]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "解析超时"}
    if p.returncode != 0:
        err = (p.stderr or p.stdout or "yt-dlp failed").strip()
        # 友好化常见错误
        if "Unsupported URL" in err or "Unsupported" in err:
            return {
                "ok": False,
                "error": "该站点暂不支持。可试：视频直链(mp4)、B站等；抖音请贴完整分享文案。",
            }
        if "cookies" in err.lower():
            return {"ok": False, "error": "该站点需要登录 Cookie，当前服务器无法解析。"}
        if len(err) > 200:
            err = err[:200] + "…"
        return {"ok": False, "error": err}

    try:
        info = json.loads(p.stdout)
    except Exception as e:
        return {"ok": False, "error": f"JSON 解析失败: {e}"}

    title = info.get("title") or "video"
    ext = info.get("ext") or "mp4"
    direct = info.get("url")
    formats = info.get("formats") or []
    candidates = []
    for f in formats:
        u = f.get("url")
        if not u or not str(u).startswith("http"):
            continue
        vcodec = f.get("vcodec") or "none"
        height = f.get("height") or 0
        if vcodec != "none":
            candidates.append((height, u, f.get("ext") or ext))
    candidates.sort(key=lambda x: x[0], reverse=True)
    if candidates:
        direct = candidates[0][1]
        ext = candidates[0][2] or ext
    if not direct:
        return {"ok": False, "error": "未解析到可下载地址"}

    return {
        "ok": True,
        "title": title,
        "ext": ext,
        "url": direct,
        "webpage_url": info.get("webpage_url") or url,
        "duration": info.get("duration"),
        "thumbnail": info.get("thumbnail"),
        "extractor": info.get("extractor"),
    }


def download_url(url: str) -> dict:
    # 先 resolve 拿直链，再由服务端下（可选）
    r = resolve_url(url)
    if not r.get("ok"):
        return r
    media = r["url"]
    jid = safe_id(url)
    out_dir = os.path.join(DATA, jid)
    os.makedirs(out_dir, exist_ok=True)
    title = re.sub(r"[\\/:*?\"<>|]+", "_", r.get("title") or "video")[:60]
    ext = r.get("ext") or "mp4"
    out = os.path.join(out_dir, f"{title}.{ext}")
    if os.path.isfile(out) and os.path.getsize(out) > 1024:
        return {
            "ok": True,
            "id": jid,
            "filename": os.path.basename(out),
            "size": os.path.getsize(out),
            "file_url": f"/app/ytdlp/file/{jid}/{os.path.basename(out)}",
            "cached": True,
            "title": r.get("title"),
        }
    try:
        req = Request(media, headers={"User-Agent": UA, "Referer": "https://www.douyin.com/"})
        with urlopen(req, timeout=120, context=CTX) as resp, open(out, "wb") as f:
            shutil.copyfileobj(resp, f, length=256 * 1024)
    except Exception as e:
        return {"ok": False, "error": f"下载失败: {e}"}
    return {
        "ok": True,
        "id": jid,
        "filename": os.path.basename(out),
        "size": os.path.getsize(out),
        "file_url": f"/app/ytdlp/file/{jid}/{os.path.basename(out)}",
        "cached": False,
        "title": r.get("title"),
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args), flush=True)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def do_POST(self):
        path = urlparse(self.path).path
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n > 0 else b"{}"
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            _json(self, 400, {"ok": False, "error": "invalid json"})
            return
        url = (body.get("url") or "").strip()
        if not url:
            _json(self, 400, {"ok": False, "error": "请提供链接或分享文案"})
            return

        if path == "/app/ytdlp/resolve":
            _json(self, 200, resolve_url(url))
            return
        if path == "/app/ytdlp/download":
            mode = (body.get("mode") or "resolve").lower()
            if mode == "server":
                _json(self, 200, download_url(url))
            else:
                _json(self, 200, resolve_url(url))
            return
        _json(self, 404, {"ok": False, "error": "not found"})

    def do_GET(self):
        u = urlparse(self.path)
        if u.path in ("/app/ytdlp/health", "/health"):
            _json(self, 200, {"ok": True, "service": "ytdlp", "version": "2-douyin"})
            return
        m = re.match(r"^/app/ytdlp/file/([a-f0-9]{16})/(.+)$", u.path)
        if not m:
            self.send_error(404)
            return
        jid, name = m.group(1), unquote(m.group(2))
        name = os.path.basename(name)
        path = os.path.join(DATA, jid, name)
        if not os.path.isfile(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.send_header(
            "Content-Disposition",
            'attachment; filename="%s"' % name.replace('"', ""),
        )
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        with open(path, "rb") as f:
            shutil.copyfileobj(f, self.wfile, length=1024 * 256)


if __name__ == "__main__":
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("[ytdlp] listening %s (douyin share support)" % PORT, flush=True)
    httpd.serve_forever()
