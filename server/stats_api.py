#!/usr/bin/env python3
"""
用户统计 + 下载跳转 API：
  短路径（stats.alucard.top / api.alucard.top 网关）：
    POST /e 或 /event          埋点
    GET  /summary              汇总（?token=）
    GET  /get 或 /download     记下载后 302 到 APK
    GET  /health
  兼容旧路径：/app/stats/*
"""
from __future__ import annotations

from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, quote
from datetime import datetime, timezone, timedelta
from urllib.request import Request, urlopen
import ipaddress
import json
import os
import re
import threading
import time
import hashlib

PORT = 3606
DATA = "/opt/film/data/stats"
# 本地开发兜底
if not os.path.isdir("/opt/film/data"):
    DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "stats")
os.makedirs(DATA, exist_ok=True)

DEVICES = os.path.join(DATA, "devices.json")
EVENTS = os.path.join(DATA, "events.jsonl")
META = os.path.join(DATA, "meta.json")
GEO_CACHE = os.path.join(DATA, "geo_cache.json")
VERSION_JSON = (
    "/opt/film/data/nginx/html/app/version.json"
    if os.path.isdir("/opt/film/data")
    else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "release", "version.json")
)
APK_DIR = (
    "/opt/film/data/nginx/html/app"
    if os.path.isdir("/opt/film/data")
    else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "release")
)

# 简易查看口令（可改）；空则 summary 公开
SUMMARY_TOKEN = os.environ.get("STATS_TOKEN", "alucard")

TZ = timezone(timedelta(hours=8))
_lock = threading.Lock()
_geo_lock = threading.Lock()
_geo_mem: dict[str, dict] = {}


def _now() -> datetime:
    return datetime.now(TZ)


def _today() -> str:
    return _now().strftime("%Y-%m-%d")


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
    handler.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Stats-Token")
    handler.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _read_body(handler: BaseHTTPRequestHandler) -> bytes:
    n = int(handler.headers.get("Content-Length") or "0")
    if n <= 0:
        return b""
    return handler.rfile.read(min(n, 1_000_000))


def _sanitize_device_id(raw: str) -> str:
    s = re.sub(r"[^A-Za-z0-9_\-:]", "", (raw or "").strip())[:80]
    return s or "unknown"


def _client_ip(handler: BaseHTTPRequestHandler) -> str:
    """优先取反代透传的真实 IP。"""
    xff = (handler.headers.get("X-Forwarded-For") or "").strip()
    if xff:
        # 可能是 client, proxy1, proxy2
        first = xff.split(",")[0].strip()
        if first:
            return first
    for h in ("X-Real-IP", "CF-Connecting-IP", "True-Client-IP"):
        v = (handler.headers.get(h) or "").strip()
        if v:
            return v
    try:
        return handler.client_address[0]
    except Exception:
        return ""


def _is_public_ip(ip: str) -> bool:
    try:
        obj = ipaddress.ip_address(ip)
        return not (
            obj.is_private
            or obj.is_loopback
            or obj.is_link_local
            or obj.is_multicast
            or obj.is_reserved
            or obj.is_unspecified
        )
    except Exception:
        return False


def _normalize_region(country: str, region: str, city: str) -> dict:
    country = (country or "").strip() or "未知"
    region = (region or "").strip()
    city = (city or "").strip()

    # 中国省份展示统一：去掉「省/市/自治区」过长尾巴时保留原样更清晰
    if country in ("中国", "China", "CN"):
        country = "中国"
        label = region or city or "中国"
        # 港澳台
        if region in ("香港", "香港特别行政区", "Hong Kong"):
            label = "香港"
        elif region in ("澳门", "澳门特别行政区", "Macao", "Macau"):
            label = "澳门"
        elif region in ("台湾", "台湾省", "Taiwan"):
            label = "台湾"
    else:
        label = region or country
        if country != "未知" and region:
            label = f"{country}·{region}"
        elif country != "未知":
            label = country

    if not label:
        label = "未知"
    return {
        "country": country,
        "region": region or "未知",
        "city": city or "",
        "label": label,
    }


def _http_get_bytes(url: str, timeout: float = 2.5) -> bytes:
    req = Request(url, headers={"User-Agent": "Mozilla/5.0 AlucardStats/1.2"}, method="GET")
    with urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _lookup_geo_pconline(ip: str) -> dict | None:
    """太平洋电脑网 whois（国内可达，GBK）。"""
    url = f"https://whois.pconline.com.cn/ipJson.jsp?ip={quote(ip)}&json=true"
    try:
        raw = _http_get_bytes(url, timeout=2.5)
        text = raw.decode("gbk", errors="replace").strip()
        # 偶发带 BOM / 空白
        if not text.startswith("{"):
            i = text.find("{")
            if i >= 0:
                text = text[i:]
        data = json.loads(text)
        if not isinstance(data, dict):
            return None
        pro = str(data.get("pro") or "").strip()
        city = str(data.get("city") or "").strip()
        addr = str(data.get("addr") or "").strip()
        err = str(data.get("err") or "").strip()
        if pro or city:
            return _normalize_region("中国", pro, city)
        # 境外或无省份：从 addr 粗解析，如「美国 APNIC...」
        if addr:
            country = addr.split()[0] if addr.split() else "未知"
            if country in ("中国",):
                return _normalize_region("中国", "", "")
            return _normalize_region(country, "", "")
        if err == "noprovince" and not pro:
            return _normalize_region("未知", "", "")
        return None
    except Exception:
        return None


def _lookup_geo_ipapi(ip: str) -> dict | None:
    """ip-api.com 备用（部分机房不可达）。"""
    url = (
        f"http://ip-api.com/json/{quote(ip)}"
        f"?lang=zh-CN&fields=status,message,country,regionName,city,query"
    )
    try:
        raw = _http_get_bytes(url, timeout=2.0).decode("utf-8", errors="replace")
        data = json.loads(raw)
        if not isinstance(data, dict) or data.get("status") != "success":
            return None
        return _normalize_region(
            str(data.get("country") or ""),
            str(data.get("regionName") or ""),
            str(data.get("city") or ""),
        )
    except Exception:
        return None


def _lookup_geo_qqwry(ip: str) -> dict | None:
    """本地纯真库（若已安装 qqwry-py3 且有 dat 文件）。"""
    dat = os.environ.get("QQWRY_DAT", os.path.join(DATA, "qqwry.dat"))
    if not os.path.isfile(dat):
        return None
    try:
        from qqwry import QQwry  # type: ignore

        q = getattr(_lookup_geo_qqwry, "_db", None)
        if q is None:
            q = QQwry()
            q.load_file(dat)
            _lookup_geo_qqwry._db = q  # type: ignore[attr-defined]
        res = q.lookup(ip)
        if not res:
            return None
        # res 通常 (国家/省, 运营商) 或类似
        if isinstance(res, (list, tuple)) and res:
            area = str(res[0] or "")
        else:
            area = str(res)
        area = area.strip()
        if not area:
            return None
        # 常见：「广东省广州市」「日本」「中国」
        country, region, city = "未知", "", ""
        if area.startswith("中国") or "省" in area or "市" in area or "自治区" in area:
            country = "中国"
            # 粗分：xx省yy市
            m = re.match(r"^(.*?省|.*?自治区|.*?特别行政区|北京|上海|天津|重庆)(.*)$", area)
            if m:
                region = m.group(1)
                rest = m.group(2) or ""
                m2 = re.match(r"^(.*?市)", rest)
                city = m2.group(1) if m2 else rest[:20]
            else:
                region = area
        else:
            country = area.split()[0] if area else "未知"
            region = area
        return _normalize_region(country, region, city)
    except Exception:
        return None


def _lookup_geo_remote(ip: str) -> dict | None:
    """多源解析：国内接口优先，离线库次之，国外接口最后。"""
    for fn in (_lookup_geo_pconline, _lookup_geo_qqwry, _lookup_geo_ipapi):
        try:
            geo = fn(ip)
        except Exception:
            geo = None
        if geo and geo.get("label") and geo.get("label") != "未知":
            return geo
        if geo and (geo.get("country") or geo.get("region")):
            # 即使 label 弱也返回（后续 normalize 过）
            if geo.get("country") != "未知" or geo.get("region") not in ("", "未知"):
                return geo
    return None


def resolve_geo(ip: str) -> dict:
    """
    解析 IP 归属地，结果写入内存 + 磁盘缓存。
    返回: country/region/city/label
    """
    ip = (ip or "").strip()
    if not ip or not _is_public_ip(ip):
        return {"country": "未知", "region": "未知", "city": "", "label": "未知"}

    with _geo_lock:
        if ip in _geo_mem:
            return dict(_geo_mem[ip])
        disk = _load_json(GEO_CACHE, {})
        if isinstance(disk, dict) and isinstance(disk.get(ip), dict):
            g = disk[ip]
            # 兼容旧缓存字段；未知结果允许重新解析
            if "label" not in g:
                g = _normalize_region(g.get("country", ""), g.get("region", ""), g.get("city", ""))
            if g.get("label") and g.get("label") != "未知":
                _geo_mem[ip] = g
                return dict(g)

    geo = _lookup_geo_remote(ip)
    if not geo:
        geo = {"country": "未知", "region": "未知", "city": "", "label": "未知"}

    with _geo_lock:
        _geo_mem[ip] = geo
        disk = _load_json(GEO_CACHE, {})
        if not isinstance(disk, dict):
            disk = {}
        # 未知不永久写死，避免首次失败后永远未知
        if geo.get("label") != "未知":
            disk[ip] = geo
        if len(disk) > 20000:
            for k in list(disk.keys())[:2000]:
                disk.pop(k, None)
        try:
            _save_json(GEO_CACHE, disk)
        except Exception:
            pass
    return dict(geo)


def _version_info() -> dict:
    info = _load_json(VERSION_JSON, {})
    if not isinstance(info, dict):
        info = {}
    return info


def _resolve_apk_url(v: str | None = None) -> str | None:
    info = _version_info()
    url = (info.get("apkUrl") or info.get("downloadUrl") or "").strip()
    if v and v not in ("latest", "current"):
        name = f"Alucard-{v}.apk"
        path = os.path.join(APK_DIR, name)
        if os.path.isfile(path):
            return f"https://ota.alucard.top/{quote(name)}"
    if url:
        return url
    latest = os.path.join(APK_DIR, "gofilm-latest.apk")
    if os.path.isfile(latest):
        return "https://ota.alucard.top/latest.apk"
    try:
        files = sorted(
            [f for f in os.listdir(APK_DIR) if f.startswith("Alucard-") and f.endswith(".apk")],
            reverse=True,
        )
        if files:
            return f"https://ota.alucard.top/{quote(files[0])}"
    except Exception:
        pass
    return None


def record_event(payload: dict, client_ip: str = "") -> dict:
    device_id = _sanitize_device_id(str(payload.get("deviceId") or ""))
    event = str(payload.get("event") or "open").strip()[:40] or "open"
    version_name = str(payload.get("versionName") or "")[:32]
    try:
        version_code = int(payload.get("versionCode") or 0)
    except Exception:
        version_code = 0
    channel = str(payload.get("channel") or "app")[:32]
    model = str(payload.get("model") or "")[:64]
    sdk = str(payload.get("sdk") or "")[:16]
    now = _now()
    ts = int(now.timestamp())
    day = now.strftime("%Y-%m-%d")

    # IP / 区域（在锁外做远程查询，避免卡住写库）
    ip = (client_ip or str(payload.get("ip") or "")).strip()[:64]
    geo = resolve_geo(ip) if ip else {"country": "未知", "region": "未知", "city": "", "label": "未知"}

    with _lock:
        devices = _load_json(DEVICES, {})
        if not isinstance(devices, dict):
            devices = {}
        d = devices.get(device_id) or {
            "deviceId": device_id,
            "firstSeen": ts,
            "firstDay": day,
            "opens": 0,
            "downloads": 0,
        }
        d["lastSeen"] = ts
        d["lastDay"] = day
        d["versionName"] = version_name or d.get("versionName", "")
        d["versionCode"] = version_code or d.get("versionCode", 0)
        d["channel"] = channel or d.get("channel", "app")
        if model:
            d["model"] = model
        if sdk:
            d["sdk"] = sdk
        if ip and _is_public_ip(ip):
            d["ip"] = ip
            d["ipUpdatedAt"] = ts
        # 有有效归属地则更新；未知不覆盖已有真实地区
        if geo.get("label") and geo.get("label") != "未知":
            d["country"] = geo.get("country") or ""
            d["region"] = geo.get("region") or ""
            d["city"] = geo.get("city") or ""
            d["regionLabel"] = geo.get("label") or ""
        elif not d.get("regionLabel"):
            d["country"] = geo.get("country") or "未知"
            d["region"] = geo.get("region") or "未知"
            d["city"] = geo.get("city") or ""
            d["regionLabel"] = geo.get("label") or "未知"

        if event in ("open", "launch", "ping", "active"):
            d["opens"] = int(d.get("opens") or 0) + 1
        if event in ("download", "apk_download"):
            d["downloads"] = int(d.get("downloads") or 0) + 1
        devices[device_id] = d
        _save_json(DEVICES, devices)

        meta = _load_json(META, {"totalDownloads": 0, "totalEvents": 0})
        meta["totalEvents"] = int(meta.get("totalEvents") or 0) + 1
        if event in ("download", "apk_download"):
            meta["totalDownloads"] = int(meta.get("totalDownloads") or 0) + 1
        meta["updatedAt"] = ts
        _save_json(META, meta)

        line = {
            "ts": ts,
            "day": day,
            "deviceId": device_id,
            "event": event,
            "versionName": version_name,
            "versionCode": version_code,
            "channel": channel,
            "ip": ip if ip and _is_public_ip(ip) else "",
            "region": d.get("regionLabel") or "",
        }
        with open(EVENTS, "a", encoding="utf-8") as f:
            f.write(json.dumps(line, ensure_ascii=False) + "\n")

    return {
        "ok": True,
        "deviceId": device_id,
        "event": event,
        "region": d.get("regionLabel") or "未知",
    }


def build_summary() -> dict:
    with _lock:
        devices = _load_json(DEVICES, {})
        meta = _load_json(META, {})
    if not isinstance(devices, dict):
        devices = {}
    today = _today()
    total_users = len(devices)
    dau = 0
    new_today = 0
    version_dist: dict[str, int] = {}
    region_dist: dict[str, int] = {}
    city_dist: dict[str, int] = {}
    country_dist: dict[str, int] = {}

    for d in devices.values():
        if not isinstance(d, dict):
            continue
        if d.get("lastDay") == today:
            dau += 1
        if d.get("firstDay") == today:
            new_today += 1
        vn = str(d.get("versionName") or "unknown")
        version_dist[vn] = version_dist.get(vn, 0) + 1

        label = str(d.get("regionLabel") or "").strip()
        if not label:
            # 兼容仅有 region/country 的旧数据
            label = _normalize_region(
                str(d.get("country") or ""),
                str(d.get("region") or ""),
                str(d.get("city") or ""),
            )["label"]
        region_dist[label] = region_dist.get(label, 0) + 1

        country = str(d.get("country") or "").strip() or "未知"
        if country in ("China", "CN"):
            country = "中国"
        country_dist[country] = country_dist.get(country, 0) + 1

        city = str(d.get("city") or "").strip()
        region = str(d.get("region") or "").strip()
        if city and region and city != region:
            city_key = f"{region}·{city}"
        elif city:
            city_key = city
        elif region:
            city_key = region
        else:
            city_key = "未知"
        city_dist[city_key] = city_dist.get(city_key, 0) + 1

    # 近 7 日活跃
    days = []
    for i in range(6, -1, -1):
        day = (_now() - timedelta(days=i)).strftime("%Y-%m-%d")
        n = sum(1 for d in devices.values() if isinstance(d, dict) and d.get("lastDay") == day)
        days.append({"day": day, "dau": n})

    # 近 7 日下载（扫事件文件尾部）
    download_by_day: dict[str, int] = {x["day"]: 0 for x in days}
    try:
        with open(EVENTS, "r", encoding="utf-8") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - 500_000))
            if size > 500_000:
                f.readline()
            for line in f:
                try:
                    ev = json.loads(line)
                except Exception:
                    continue
                if ev.get("event") in ("download", "apk_download") and ev.get("day") in download_by_day:
                    download_by_day[ev["day"]] += 1
    except FileNotFoundError:
        pass

    # 排序列表，方便前端直接渲染
    region_list = [
        {"region": k, "users": v, "ratio": round(v * 100.0 / total_users, 1) if total_users else 0}
        for k, v in sorted(region_dist.items(), key=lambda x: (-x[1], x[0]))
    ]
    city_list = [
        {"city": k, "users": v, "ratio": round(v * 100.0 / total_users, 1) if total_users else 0}
        for k, v in sorted(city_dist.items(), key=lambda x: (-x[1], x[0]))
    ][:50]
    country_list = [
        {"country": k, "users": v, "ratio": round(v * 100.0 / total_users, 1) if total_users else 0}
        for k, v in sorted(country_dist.items(), key=lambda x: (-x[1], x[0]))
    ]

    known_region_users = sum(v for k, v in region_dist.items() if k != "未知")
    unknown_region_users = int(region_dist.get("未知") or 0)

    return {
        "ok": True,
        "generatedAt": _now().isoformat(),
        "totalUsers": total_users,
        "dauToday": dau,
        "newUsersToday": new_today,
        "totalDownloads": int(meta.get("totalDownloads") or 0),
        "totalEvents": int(meta.get("totalEvents") or 0),
        "versionDist": dict(sorted(version_dist.items(), key=lambda x: -x[1])),
        "regionDist": dict(sorted(region_dist.items(), key=lambda x: -x[1])),
        "regionList": region_list,
        "cityList": city_list,
        "countryList": country_list,
        "regionKnownUsers": known_region_users,
        "regionUnknownUsers": unknown_region_users,
        "dauLast7Days": days,
        "downloadsLast7Days": [{"day": k, "count": v} for k, v in download_by_day.items()],
        "app": _version_info(),
    }


def _token_ok(handler: BaseHTTPRequestHandler, qs: dict) -> bool:
    if not SUMMARY_TOKEN:
        return True
    t = (qs.get("token") or [""])[0]
    if t == SUMMARY_TOKEN:
        return True
    auth = handler.headers.get("X-Stats-Token") or handler.headers.get("Authorization") or ""
    auth = auth.replace("Bearer", "").strip()
    return auth == SUMMARY_TOKEN


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[stats]", fmt % args, flush=True)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Stats-Token")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()

    def do_GET(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        qs = parse_qs(u.query)

        if path in ("/health", "/app/stats/health") or path.endswith("/health"):
            _json(self, 200, {"ok": True, "service": "stats", "version": "2-region"})
            return

        if path in ("/summary", "/app/stats/summary") or path.endswith("/summary"):
            if not _token_ok(self, qs):
                _json(self, 401, {"ok": False, "error": "unauthorized"})
                return
            _json(self, 200, build_summary())
            return

        if path in ("/get", "/download", "/app/stats/download") or path.endswith(
            "/download"
        ) or path.endswith("/get"):
            device = (qs.get("deviceId") or ["web"])[0]
            ver = (qs.get("v") or ["latest"])[0]
            ip = _client_ip(self)
            record_event(
                {
                    "deviceId": device
                    if device != "web"
                    else f"web-{hashlib.sha1((ip + str(time.time())).encode()).hexdigest()[:12]}",
                    "event": "download",
                    "versionName": ver,
                    "versionCode": 0,
                    "channel": "web",
                },
                client_ip=ip,
            )
            apk = _resolve_apk_url(ver)
            if not apk:
                _json(self, 404, {"ok": False, "error": "apk not found"})
                return
            self.send_response(302)
            self.send_header("Location", apk)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            return

        _json(self, 404, {"ok": False, "error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        if path in ("/e", "/event", "/app/stats/event") or path.endswith("/event") or path.endswith("/e"):
            raw = _read_body(self)
            try:
                payload = json.loads(raw.decode("utf-8") or "{}")
            except Exception:
                _json(self, 400, {"ok": False, "error": "invalid json"})
                return
            if not isinstance(payload, dict):
                _json(self, 400, {"ok": False, "error": "invalid body"})
                return
            try:
                r = record_event(payload, client_ip=_client_ip(self))
                _json(self, 200, r)
            except Exception as e:
                _json(self, 500, {"ok": False, "error": str(e)})
            return
        _json(self, 404, {"ok": False, "error": "not found"})


def main():
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[stats] listening {PORT} data={DATA}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
