#!/usr/bin/env python3
import re
import json
import urllib.request

h = open("/tmp/dy.html", "r", encoding="utf-8", errors="ignore").read()
h2 = h.replace("\\u002F", "/").replace("\\/", "/")

urls = re.findall(r"https://aweme\.snssdk\.com/aweme/v1/playwm/\?[^\"'\\s]+", h2)
print("wm", len(urls))
if urls:
    u = urls[0]
    print("wm url", u[:200])
    for candidate in [u, u.replace("playwm", "play")]:
        print("try", candidate[:120])
        req = urllib.request.Request(
            candidate,
            headers={
                "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)",
                "Referer": "https://www.douyin.com/",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                ct = r.headers.get("Content-Type", "")
                cl = r.headers.get("Content-Length", "")
                print("  final", r.geturl()[:140], "ct", ct, "cl", cl)
                b = r.read(32)
                print("  head", b[:20], "ftyp", b[4:8] == b"ftyp")
        except Exception as e:
            print("  err", e)

m = re.search(r"window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*;?\s*</script>", h, re.S)
raw = m.group(1)
data = json.loads(raw)
found = []


def walk(o, path=""):
    if isinstance(o, dict):
        if "url_list" in o and isinstance(o["url_list"], list):
            for u in o["url_list"]:
                if isinstance(u, str) and u.startswith("http"):
                    found.append((path, u))
        for k, v in o.items():
            walk(v, path + "." + str(k))
    elif isinstance(o, list):
        for i, v in enumerate(o[:80]):
            walk(v, path + f"[{i}]")


walk(data)
print("found", len(found))
for p, u in found:
    if any(x in u for x in ["play", "video", "snssdk", "bytevod", "douyinvod", "aweme"]):
        print(p[-80:], "=>", u[:160])
