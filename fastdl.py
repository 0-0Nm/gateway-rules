"""加速下载 GitHub artifact: 8 线程分段 + Bearer 鉴权"""
import os, sys, threading, urllib.request

TOKEN = "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"
URL = "https://api.github.com/repos/0-0Nm/gateway-rules/actions/artifacts/12629059354/zip"
OUT = "/tmp/apk3/a_full.zip"
PARTS = 8

def head_size():
    req = urllib.request.Request(URL, method="HEAD")
    req.add_header("Authorization", "Bearer " + TOKEN)
    # GitHub 会 302 到 Azure blob；跟踪后取 Content-Length
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k): return None
    opener = urllib.request.build_opener(NoRedirect)
    try:
        r = opener.open(req, timeout=30)
        return int(r.headers.get("Content-Length", 0)), r.headers.get("Location")
    except urllib.error.HTTPError as e:
        return int(e.headers.get("Content-Length", 0)), e.headers.get("Location")

def download_range(url, start, end, idx, results):
    req = urllib.request.Request(url)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Range", "bytes=%d-%d" % (start, end))
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
        results[idx] = data
        print("part %d done: %d bytes" % (idx, len(data)))
    except Exception as e:
        print("part %d FAIL: %r" % (idx, e))
        results[idx] = None

def main():
    size, loc = head_size()
    if size <= 0:
        print("cannot determine size"); sys.exit(1)
    print("total size:", size)
    chunk = size // PARTS
    ranges = []
    for i in range(PARTS):
        s = i * chunk
        e = (size - 1) if i == PARTS - 1 else (s + chunk - 1)
        ranges.append((s, e))
    results = [None] * PARTS
    # 首段走原始 URL (带302跟踪)，其余段也直接用原始 URL (urllib 默认跟随302)
    threads = []
    for i, (s, e) in enumerate(ranges):
        t = threading.Thread(target=download_range, args=(URL, s, e, i, results))
        t.start(); threads.append(t)
    for t in threads: t.join()

    if any(r is None for r in results):
        print("SOME PARTS FAILED"); sys.exit(2)
    with open(OUT, "wb") as f:
        for r in results:
            f.write(r)
    print("written:", OUT, os.path.getsize(OUT), "bytes")

if __name__ == "__main__":
    main()
