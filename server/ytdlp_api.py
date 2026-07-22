#!/usr/bin/env python3
"""
轻量解析 + 下载 API：
  短路径（api.alucard.top 网关）：
    GET  /health 或 /health/r
    POST /r                 解析 {"url":"..."}
    POST /d                 代下 {"url":"...","mode":"resolve|server"}
    GET  /f/{id}/{name}     文件
  兼容旧路径：/app/ytdlp/*
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


def referer_for_media(media_url: str, page_url: str = "") -> str:
    """按 CDN / 页面域名选 Referer，提高各站下载成功率。"""
    u = f"{media_url} {page_url}".lower()
    rules = [
        (("douyin", "byte", "snssdk", "aweme", "douyinvod", "iesdouyin"), "https://www.douyin.com/"),
        (("kuaishou", "gifshow", "yximgs", "kwimgs"), "https://www.kuaishou.com/"),
        (("xiaohongshu", "xhscdn", "xhslink"), "https://www.xiaohongshu.com/"),
        (("bilibili", "bilivideo", "hdslb", "b23.tv"), "https://www.bilibili.com/"),
        (("weibo", "sinaimg", "miaopai"), "https://weibo.com/"),
        (("zhihu", "zhimg"), "https://www.zhihu.com/"),
        (("ixigua", "toutiao", "pstatp", "ibytedtos"), "https://www.ixigua.com/"),
        (("pipix",), "https://www.pipix.com/"),
        (("haokan",), "https://haokan.baidu.com/"),
        (("weishi", "isee"), "https://weishi.qq.com/"),
        (("tiktok", "muscdn", "tiktokv"), "https://www.tiktok.com/"),
    ]
    for keys, ref in rules:
        if any(k in u for k in keys):
            return ref
    if page_url.startswith("http"):
        return page_url.split("?")[0].rsplit("/", 1)[0] + "/"
    return "https://www.baidu.com/"


def extract_first_url(text: str) -> str:
    """从分享文案里抠出 http(s) 链接（国内主流平台短链优先）。"""
    text = (text or "").strip()
    if not text:
        return ""
    patterns = [
        # 抖音
        r"https?://v\.douyin\.com/[A-Za-z0-9_\-]+/?",
        r"https?://www\.douyin\.com/video/\d+[^\s]*",
        r"https?://www\.iesdouyin\.com/share/video/\d+[^\s]*",
        # 快手
        r"https?://v\.kuaishou(?:app)?\.com/[A-Za-z0-9_\-]+/?",
        r"https?://(?:www\.)?kuaishou\.com/(?:short-video|f)/[A-Za-z0-9_\-]+[^\s]*",
        r"https?://(?:www\.)?gifshow\.com/[^\s]+",
        # 小红书
        r"https?://xhslink\.com/[A-Za-z0-9/\-_]+/?",
        r"https?://(?:www\.)?xiaohongshu\.com/(?:explore|discovery/item|video)/[A-Za-z0-9]+[^\s]*",
        # B站
        r"https?://b23\.tv/[A-Za-z0-9]+/?",
        r"https?://(?:www\.|m\.)?bilibili\.com/(?:video|bangumi)/[^\s]+",
        # 微博
        r"https?://t\.cn/[A-Za-z0-9]+/?",
        r"https?://(?:m\.)?weibo\.(?:com|cn)/[^\s]+",
        r"https?://video\.weibo\.com/[^\s]+",
        # 知乎
        r"https?://(?:www\.)?zhihu\.com/(?:zvideo|video|answer|question)/\S+",
        r"https?://(?:v|oia)\.zhihu\.com/[^\s]+",
        # 西瓜 / 头条
        r"https?://(?:www\.|m\.)?ixigua\.com/[^\s]+",
        r"https?://v\.ixigua\.com/[A-Za-z0-9]+/?",
        r"https?://(?:www\.|m\.)?toutiao\.com/[^\s]+",
        # 皮皮虾
        r"https?://(?:h5\.)?pipix\.com/[^\s]+",
        # 好看视频
        r"https?://haokan\.baidu\.com/[^\s]+",
        # 微视
        r"https?://(?:isee\.weishi|weishi)\.qq\.com/[^\s]+",
        # 火山
        r"https?://(?:www\.|share\.)?huoshan\.com/[^\s]+",
        # TikTok
        r"https?://(?:www\.)?tiktok\.com/[^\s]+",
        r"https?://vm\.tiktok\.com/[A-Za-z0-9]+/?",
        # 兜底
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


def http_get(url: str, timeout: int = 20, referer: str | None = None) -> tuple[str, str]:
    """返回 (final_url, body_text)。"""
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    req = Request(url, headers=headers)
    with urlopen(req, timeout=timeout, context=CTX) as r:
        final = r.geturl()
        body = r.read().decode("utf-8", "ignore")
        return final, body


def _parse_js_object(raw: str):
    """解析页面内嵌 JS 对象（处理 undefined）。"""
    text = re.sub(r"\bundefined\b", "null", raw.strip().rstrip(";"))
    return json.loads(text)


def _collect_master_urls(obj, out: list):
    """递归收集 masterUrl / originVideoKey 等字段。"""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k in ("masterUrl", "master_url", "url") and isinstance(v, str):
                if v.startswith("http") and (
                    ".mp4" in v.lower()
                    or "xhscdn.com" in v
                    or "sns-video" in v
                ):
                    out.append(v)
            if k in ("originVideoKey", "origin_video_key") and isinstance(v, str) and v.strip():
                key = v.strip().lstrip("/")
                # 无水印原片常见 CDN
                for host in (
                    "https://sns-video-bd.xhscdn.com/",
                    "https://sns-video-al.xhscdn.com/",
                    "https://sns-video-qc.xhscdn.com/",
                ):
                    out.append(host + key)
            if k == "mediaV2" and isinstance(v, str) and v.startswith("{"):
                try:
                    _collect_master_urls(json.loads(v), out)
                except Exception:
                    pass
            _collect_master_urls(v, out)
    elif isinstance(obj, list):
        for item in obj:
            _collect_master_urls(item, out)


def resolve_xiaohongshu(url: str) -> dict | None:
    """解析小红书分享短链 / 笔记页（绕过 yt-dlp 抽不到 formats 的问题）。"""
    if not re.search(r"xiaohongshu\.com|xhslink\.com", url, re.I):
        return None
    try:
        final, html = http_get(
            url,
            timeout=25,
            referer="https://www.xiaohongshu.com/",
        )
    except Exception as e:
        return {"ok": False, "error": f"打开小红书链接失败: {e}"}

    # 图文笔记
    if "type=normal" in final and "type=video" not in final:
        # 仍可能是视频，继续往下解析
        pass

    title = "xiaohongshu_video"
    data = None
    m = re.search(
        r"window\.__INITIAL_STATE__\s*=\s*(\{.+?)\s*</script>",
        html,
        re.S,
    )
    if m:
        try:
            data = _parse_js_object(m.group(1))
        except Exception as e:
            print(f"[xhs] initial_state json err {e}", flush=True)

    note = None
    if isinstance(data, dict):
        # 分享页常见：noteData.data.noteData
        nd = ((data.get("noteData") or {}).get("data") or {}).get("noteData")
        if isinstance(nd, dict) and nd:
            note = nd
        # 桌面站：note.noteDetailMap[id].note
        if note is None:
            detail_map = (data.get("note") or {}).get("noteDetailMap") or {}
            if isinstance(detail_map, dict):
                for v in detail_map.values():
                    if not isinstance(v, dict):
                        continue
                    cand = v.get("note") or v.get("noteData") or v
                    if isinstance(cand, dict) and (
                        cand.get("video") is not None or cand.get("type") == "video"
                    ):
                        note = cand
                        break
        if isinstance(note, dict):
            title = (note.get("title") or note.get("desc") or title).strip() or title

    play_urls: list[str] = []
    if note:
        _collect_master_urls(note, play_urls)
    if data and not play_urls:
        _collect_master_urls(data, play_urls)

    if not play_urls:
        # 兜底正则
        html2 = html.replace("\\u002F", "/").replace("\\/", "/")
        play_urls += re.findall(
            r"https?://sns-video[^\"'\\\s]+\.mp4[^\"'\\\s]*",
            html2,
        )
        keys = re.findall(r'"originVideoKey"\s*:\s*"([^"]+)"', html2)
        for key in keys:
            key = key.strip().lstrip("/")
            play_urls.append(f"https://sns-video-bd.xhscdn.com/{key}")

    # 去重
    seen = set()
    ordered = []
    for u in play_urls:
        u = u.replace("\\u002F", "/").replace("\\/", "/")
        if u.startswith("//"):
            u = "https:" + u
        if not u.startswith("http"):
            continue
        # 过滤封面图
        if any(x in u for x in ("!h5_", "imageView2", "format/jpg", "format/webp", "webpic")):
            continue
        if u not in seen:
            seen.add(u)
            ordered.append(u)

    if not ordered:
        ntype = ""
        if isinstance(note, dict):
            ntype = str(note.get("type") or "")
        if ntype == "normal":
            return {"ok": False, "error": "这是小红书图文笔记，没有视频可下载"}
        return {"ok": False, "error": "小红书页面未解析到视频地址（可能已失效或需登录）"}

    # 优先探测能真正下到 mp4 的地址（origin 通常画质更高）
    def _score(u: str) -> int:
        s = 0
        if "origin" in u or "spectrum/" in u:
            s += 10
        if "sns-video-bd" in u or "sns-video-al" in u:
            s += 5
        if ".mp4" in u:
            s += 2
        if "h265" in u or "_309" in u:
            s += 1
        return s

    ordered.sort(key=_score, reverse=True)

    for u in ordered:
        try:
            req = Request(
                u,
                headers={
                    "User-Agent": UA,
                    "Referer": "https://www.xiaohongshu.com/",
                },
            )
            with urlopen(req, timeout=15, context=CTX) as r:
                ct = (r.headers.get("Content-Type") or "").lower()
                cl = int(r.headers.get("Content-Length") or "0")
                head = r.read(16)
                ok = ("video" in ct or head[4:8] == b"ftyp" or cl > 50000)
                if ok:
                    final_media = r.geturl() if r.geturl().startswith("http") else u
                    return {
                        "ok": True,
                        "title": re.sub(r"\s+", " ", title)[:80],
                        "ext": "mp4",
                        "url": final_media,
                        "webpage_url": final,
                        "extractor": "xhs-share",
                        "size_hint": cl if cl > 0 else None,
                    }
        except Exception as e:
            print(f"[xhs] probe fail {e}", flush=True)
            continue

    # 探测失败仍返回分最高的一条
    return {
        "ok": True,
        "title": re.sub(r"\s+", " ", title)[:80],
        "ext": "mp4",
        "url": ordered[0],
        "webpage_url": final,
        "extractor": "xhs-share",
    }


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

    # 1b) 小红书专用（yt-dlp 常报 No video formats found）
    if re.search(r"xiaohongshu\.com|xhslink\.com", url, re.I):
        xhs = resolve_xiaohongshu(url)
        if xhs is not None:
            return xhs

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
                "error": (
                    "该站点暂不支持或需升级 yt-dlp。"
                    "可试：抖音/快手/小红书/B站/微博/知乎/西瓜等分享链接，或 mp4 直链。"
                    "微信视频号暂不支持。"
                ),
            }
        if "No video formats found" in err and re.search(
            r"XiaoHongShu|xiaohongshu|xhslink", err + url, re.I
        ):
            return {
                "ok": False,
                "error": "小红书解析失败。请确认是视频笔记（非图文），并更新服务端解析组件。",
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
            "file_url": f"/f/{jid}/{os.path.basename(out)}",
            "cached": True,
            "title": r.get("title"),
        }
    try:
        ref = referer_for_media(media, r.get("webpage_url") or url)
        req = Request(media, headers={"User-Agent": UA, "Referer": ref})
        with urlopen(req, timeout=120, context=CTX) as resp, open(out, "wb") as f:
            shutil.copyfileobj(resp, f, length=256 * 1024)
    except Exception as e:
        return {"ok": False, "error": f"下载失败: {e}"}
    return {
        "ok": True,
        "id": jid,
        "filename": os.path.basename(out),
        "size": os.path.getsize(out),
        "file_url": f"/f/{jid}/{os.path.basename(out)}",
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

        if path in ("/r", "/app/ytdlp/resolve"):
            _json(self, 200, resolve_url(url))
            return
        if path in ("/d", "/app/ytdlp/download"):
            mode = (body.get("mode") or "resolve").lower()
            if mode == "server":
                _json(self, 200, download_url(url))
            else:
                _json(self, 200, resolve_url(url))
            return
        _json(self, 404, {"ok": False, "error": "not found"})

    def do_GET(self):
        u = urlparse(self.path)
        if u.path in ("/app/ytdlp/health", "/health", "/health/r"):
            _json(self, 200, {"ok": True, "service": "ytdlp", "version": "4-xhs-share"})
            return
        m = re.match(r"^(?:/f|/app/ytdlp/file)/([a-f0-9]{16})/(.+)$", u.path)
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
    print("[ytdlp] listening %s (cn platforms + douyin share)" % PORT, flush=True)
    httpd.serve_forever()
