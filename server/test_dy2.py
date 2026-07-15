#!/usr/bin/env python3
import re, json, urllib.request

h = open("/tmp/dy.html", "r", encoding="utf-8", errors="ignore").read()
m = re.search(r"window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*;?\s*</script>", h, re.S)
data = json.loads(m.group(1))

def get_item(d):
    try:
        page = d["loaderData"]["video_(id)/page"]
        items = page["videoInfoRes"]["item_list"]
        return items[0]
    except Exception as e:
        print("no item", e)
        return None

item = get_item(data)
print("desc", (item or {}).get("desc"))
video = (item or {}).get("video") or {}
print("video keys", list(video.keys()))
for k in ["play_addr", "download_addr", "play_addr_h264", "bit_rate"]:
    print(k, video.get(k))

play = (video.get("play_addr") or {}).get("url_list") or []
print("play list", play)
for u in play:
    for candidate in [u, u.replace("playwm", "play")]:
        print("fetch", candidate[:160])
        req = urllib.request.Request(
            candidate,
            headers={
                "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15",
                "Referer": "https://www.douyin.com/",
            },
            method="GET",
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                # follow redirect
                data = r.read(64)
                print("  status", r.status, "url", r.geturl()[:120], "ct", r.headers.get("Content-Type"), "cl", r.headers.get("Content-Length"), "head", data[:16])
        except Exception as e:
            print("  err", e)

# bit_rate may have better urls
br = video.get("bit_rate") or []
print("bitrates", len(br) if isinstance(br, list) else br)
if isinstance(br, list):
    for b in br[:3]:
        pa = (b.get("play_addr") or {}).get("url_list") or []
        print(" br", b.get("gear_name"), pa[:1])
