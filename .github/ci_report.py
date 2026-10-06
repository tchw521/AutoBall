#!/usr/bin/env python3
"""把构建日志中的关键错误回传到仓库 Issue，便于在无日志下载权限时诊断。

用法：python3 .github/ci_report.py <日志文件> <标签>
"""
import json
import os
import sys
import urllib.request

TOKEN = os.environ.get("GH_TOKEN", "")
REPO = os.environ.get("GITHUB_REPOSITORY", "tchw521/AutoBall")
RUN_URL = os.environ.get("GITHUB_SERVER_URL", "https://github.com") + "/" + REPO + \
    "/actions/runs/" + os.environ.get("GITHUB_RUN_ID", "0")


def api(method, path, data=None):
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request("https://api.github.com" + path, data=body, method=method)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("Content-Type", "application/json")
    r.add_header("User-Agent", "autoball-ci")
    try:
        with urllib.request.urlopen(r, timeout=60) as x:
            t = x.read().decode()
            return json.loads(t) if t else {}
    except Exception as e:
        print("api error", e)
        return {}


def extract(path):
    if not os.path.exists(path):
        return "（无日志文件）"
    with open(path, "r", errors="replace") as f:
        lines = f.read().split("\n")
    keys = []
    for i, ln in enumerate(lines):
        s = ln.strip()
        if ("e: " in s or "error:" in s or s.startswith("* Exception") or
                "FAILED" in s or "Caused by" in s or "What went wrong" in s or
                "Unresolved reference" in s or "Execution failed" in s or
                "error: failed" in s or "CMake Error" in s):
            keys.append("%d| %s" % (i + 1, s))
    head = "\n".join(lines[:60])
    key_txt = "\n".join(keys[:120])
    out = "## 头部日志\n\n```\n%s\n```\n\n## 关键错误（最多 120 行）\n\n```\n%s\n```\n" % (head, key_txt)
    return out[:60000]


def main():
    if len(sys.argv) < 3:
        print("usage: ci_report.py <logfile> <tag>")
        return
    log_file, tag = sys.argv[1], sys.argv[2]
    body = "自动回传的构建诊断（%s）\n\n%s\n\n来源：%s\n\n%s" % (
        tag, "运行链接", RUN_URL, extract(log_file))
    title = "[ci-log] %s @ run %s" % (tag, os.environ.get("GITHUB_RUN_ID", "0"))
    issues = api("GET", "/repos/%s/issues?state=open&labels=ci-log&per_page=20" % REPO)
    exists = None
    if isinstance(issues, list):
        for it in issues:
            if it.get("title", "").startswith("[ci-log] %s" % tag):
                exists = it
                break
    if exists:
        api("PATCH", "/repos/%s/issues/%s" % (REPO, exists["number"]), {"body": body})
        print("updated issue", exists["number"])
    else:
        r = api("POST", "/repos/%s/issues" % REPO, {"title": title, "body": body, "labels": ["ci-log"]})
        print("created issue", r.get("number"))


if __name__ == "__main__":
    main()
