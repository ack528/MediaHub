#!/usr/bin/env python3
"""把 dist/release/ 里的文件发布成 GitHub Release(ack528/MediaHub)。

用法:python tools/publish-release.py <tag> <标题>
- 凭据:用 git 已经保存的 GitHub 凭据(git credential fill),不会打印出来,也不需要另外的令牌;
- 说明:取根目录 CHANGELOG.md 里手机端 / 服务端各自最新一个版本的条目;
- 只上传 dist/release/ 里的文件(不带 Lossless.dll 的 APK、-update.zip、便携版 zip、SHA256SUMS.txt);
- 同名 tag 的 Release 已经存在就先删掉重建(重新发布同一个版本时用)。
"""
import json, os, re, subprocess, sys, urllib.error, urllib.parse, urllib.request

REPO = "ack528/MediaHub"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REL = os.path.join(ROOT, "dist", "release")


def token():
    out = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", capture_output=True, text=True, timeout=30).stdout
    kv = dict(l.split("=", 1) for l in out.splitlines() if "=" in l)
    if not kv.get("password"):
        sys.exit("没有取到 git 保存的 GitHub 凭据(先 git push 一次让凭据管理器登录)")
    return kv["password"]


def api(tok, method, url, data=None, headers=None, raw=False):
    h = {"Authorization": "Bearer " + tok, "User-Agent": "mediahub-release", "Accept": "application/vnd.github+json"}
    h.update(headers or {})
    body = data if isinstance(data, (bytes, bytearray)) else (json.dumps(data).encode() if data is not None else None)
    if body is not None and "Content-Type" not in h:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            b = r.read()
            return json.loads(b) if b and not raw else b
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url} -> HTTP {e.code}: {e.read().decode('utf-8', 'ignore')[:400]}")


def latest_entry(text, section):
    inside, title, items = False, None, []
    for line in text.splitlines():
        if line.startswith("## "):
            if title and inside:
                break
            inside = section in line
        elif inside and line.startswith("### "):
            if title:
                break
            title = line[4:].strip()
        elif inside and title and line.startswith("- "):
            items.append(line)
    return title, items


def save_last():
    """发布成功后把这次的文件存一份到 dist/last-release/:下次只打包有改动的一端时,另一端沿用这里的 APK / update.zip。"""
    import glob, shutil
    last = os.path.join(ROOT, "dist", "last-release")
    os.makedirs(last, exist_ok=True)
    for pat in ("LocalBrowse-*.apk", "MediaHub-*-update.zip"):
        new = glob.glob(os.path.join(REL, pat))
        if new:  # 这次有新的:删掉旧的再复制
            for old in glob.glob(os.path.join(last, pat)):
                os.remove(old)
            for f in new:
                shutil.copy2(f, last)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    tag, name = sys.argv[1], sys.argv[2]
    files = sorted(f for f in os.listdir(REL) if os.path.isfile(os.path.join(REL, f)) and f != "SHA256SUMS.txt")
    assert files, "dist/release 是空的,先运行 tools/package.ps1 -Release"
    assert not any("dll" in f.lower() for f in files), "发布目录里不能有 DLL"
    # 手机端和服务端的更新程序都只看最新的一个 Release:安装包、更新包、SHA256SUMS.txt 缺一个就检查不了 / 校验不了更新
    assert any(re.match(r"^LocalBrowse-[\d.]+\.apk$", f) for f in files), "发布目录里没有手机端安装包(另一端没改时应由 package.ps1 从 dist/last-release 带过来)"
    assert any(re.match(r"^MediaHub-[\d.]+-update\.zip$", f) for f in files), "发布目录里没有服务端更新包"
    # SHA256SUMS.txt 在这里按实际要上传的文件重新算(以前靠打包脚本生成,那一步出错时发布就缺了校验文件)
    import hashlib
    lines = []
    for f in files:
        h = hashlib.sha256()
        with open(os.path.join(REL, f), "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        lines.append(f"{h.hexdigest()}  {f}")
    with open(os.path.join(REL, "SHA256SUMS.txt"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    files.append("SHA256SUMS.txt")
    print("SHA256SUMS.txt:\n  " + "\n  ".join(lines))
    text = open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf-8").read()
    ct, ci = latest_entry(text, "手机端")
    st, si = latest_entry(text, "服务端")
    body = f"## 手机端 LocalBrowse {ct}\n" + "\n".join(ci) + f"\n\n## 服务端 / 管理程序 MediaHub {st}\n" + "\n".join(si)
    body += "\n\n完整的历史版本见 [CHANGELOG.md](https://github.com/ack528/MediaHub/blob/main/CHANGELOG.md)。\n\n> 发布的 APK 不含 Lossless.dll(版权文件,不公开分发);补帧需自行提供已授权的 DLL。\n"
    tok = token()
    base = f"https://api.github.com/repos/{REPO}"
    try:
        old = api(tok, "GET", f"{base}/releases/tags/{urllib.parse.quote(tag)}")
        print("删除已有的同名 Release", old["id"])
        api(tok, "DELETE", f"{base}/releases/{old['id']}")
        subprocess.run(["git", "push", "origin", f":refs/tags/{tag}"], cwd=ROOT, capture_output=True)
    except SystemExit:
        pass
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    rel = api(tok, "POST", f"{base}/releases", {"tag_name": tag, "target_commitish": commit, "name": name, "body": body, "draft": True, "prerelease": False})
    print("已创建 Release:", rel["html_url"])
    up = rel["upload_url"].split("{")[0]
    for f in files:
        data = open(os.path.join(REL, f), "rb").read()
        ctype = "application/vnd.android.package-archive" if f.endswith(".apk") else ("application/zip" if f.endswith(".zip") else "text/plain")
        print("上传", f, f"{len(data) / 1048576:.1f} MB")
        api(tok, "POST", f"{up}?name={urllib.parse.quote(f)}", data, {"Content-Type": ctype})
    api(tok, "PATCH", f"{base}/releases/{rel['id']}", {"draft": False, "make_latest": "true"})
    print("完成:", rel["html_url"])
    save_last()


main()
