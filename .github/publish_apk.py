#!/usr/bin/env python3
"""把构建产物 APK 以 base64 blob 提交到仓库的 apk 分支。

用途：沙盒环境只能访问 api.github.com，无法直连对象存储下载 Artifact。
把 APK 作为 blob 存进仓库后，即可通过 Git Blobs API 取回原始字节。
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request

TOKEN = os.environ.get("GH_TOKEN", "")
REPO = os.environ.get("GITHUB_REPOSITORY", "tchw521/AutoBall")
BRANCH = os.environ.get("APK_BRANCH", "apk")
API = "https://api.github.com"


def api(method, path, data=None):
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request(API + path, data=body, method=method)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("Content-Type", "application/json")
    r.add_header("User-Agent", "autoball-ci")
    try:
        with urllib.request.urlopen(r, timeout=180) as x:
            t = x.read().decode()
            return json.loads(t) if t else {}
    except Exception as e:
        print("api error:", method, path, e)
        return {}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk"
    version = subprocess.check_output(
        ["grep", "-m1", "versionName", "app/build.gradle.kts"]).decode()
    version = version.split('"')[1] if '"' in version else "0.0.0"
    engine = os.environ.get("ENGINE", "unknown")

    tree = []
    found = []
    for dirpath, _, files in os.walk(root):
        for f in sorted(files):
            if not f.endswith(".apk"):
                continue
            p = os.path.join(dirpath, f)
            size = os.path.getsize(p)
            with open(p, "rb") as fh:
                b64 = base64.b64encode(fh.read()).decode()
            blob = api("POST", "/repos/%s/git/blobs" % REPO,
                       {"content": b64, "encoding": "base64"})
            if not blob.get("sha"):
                print("blob 创建失败", p)
                continue
            rel = "v%s/%s/%s" % (version, engine, f)
            tree.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
            found.append((rel, size))
            print("已入库", rel, size)

    if not tree:
        print("没有 APK 可入库")
        return

    # 追加一份清单，方便外部按图索骥
    manifest = json.dumps({"version": version, "engine": engine,
                           "files": [{"path": r, "size": s} for r, s in found]},
                          ensure_ascii=False, indent=2)
    mb = api("POST", "/repos/%s/git/blobs" % REPO,
             {"content": base64.b64encode(manifest.encode()).decode(), "encoding": "base64"})
    if mb.get("sha"):
        tree.append({"path": "v%s/%s/manifest.json" % (version, engine),
                     "mode": "100644", "type": "blob", "sha": mb["sha"]})

    parent = None
    try:
        parent = api("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BRANCH))["object"]["sha"]
    except Exception:
        pass
    payload = {"tree": tree}
    if parent:
        payload["base_tree"] = parent
    t = api("POST", "/repos/%s/git/trees" % REPO, payload)
    if not t.get("sha"):
        print("tree 创建失败")
        return
    c = api("POST", "/repos/%s/git/commits" % REPO, {
        "message": "apk: v%s %s 构建产物" % (version, engine),
        "tree": t["sha"],
        "parents": [parent] if parent else []})
    if not c.get("sha"):
        print("commit 创建失败")
        return
    if parent:
        r = api("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH), {"sha": c["sha"]})
    else:
        r = api("POST", "/repos/%s/git/refs" % REPO,
                {"ref": "refs/heads/%s" % BRANCH, "sha": c["sha"]})
    print("已提交到分支", BRANCH, c["sha"], r.get("object", {}).get("sha", ""))


if __name__ == "__main__":
    main()
