"""服务器端「浏览位置 / 播放进度同步」接口自测(不打印账号密码)。
用法: python testdata/scripts/sync-api-test.py [http://127.0.0.1:8481]
"""
import json, os, sys, urllib.request, urllib.error, time

base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8481"
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
acc = {}
for line in open(os.path.join(root, "runtime", "mediahub", "emu-account.txt"), encoding="utf-8-sig"):
    k, _, v = line.partition(":")
    if not _:
        k, _, v = line.partition("=")
    acc[k.strip()] = v.strip()


def call(method, path, body=None, token=None):
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            t = r.read().decode()
            return r.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


st, r = call("POST", "/api/v1/auth/login", {"username": acc["username"], "password": acc["password"], "deviceName": "sync-test"})
print("login", st)
tok = r["token"]
st, d = call("GET", "/api/v1/dialogs?limit=5", token=tok)
did = d["dialogs"][0]["id"]
print("dialog", did)
view = {"itemId": "123", "offsetPx": 77, "sort": "name", "ascChat": False, "ascGrid": True, "grid": True, "types": ["photo"], "columns": 4, "savedAt": int(time.time() * 1000)}
print("PUT view", call("PUT", f"/api/v1/dialogs/{did}/view", view, tok))
st, g = call("GET", f"/api/v1/dialogs/{did}/view", token=tok)
print("GET view", st, g)
older = dict(view, sort="taken", savedAt=view["savedAt"] - 100000)
print("PUT older", call("PUT", f"/api/v1/dialogs/{did}/view", older, tok))
st, g2 = call("GET", f"/api/v1/dialogs/{did}/view", token=tok)
print("GET after older (应仍是 name)", st, g2["json"]["sort"] if isinstance(g2, dict) else g2)
mid = d["dialogs"][0]["last"]["id"] if d["dialogs"][0].get("last") else None
if mid:
    print("PUT playback", call("PUT", f"/api/v1/media/{mid}/playback", {"posMs": 4321, "savedAt": int(time.time() * 1000)}, tok))
    print("GET playback", call("GET", f"/api/v1/media/{mid}/playback", token=tok))
