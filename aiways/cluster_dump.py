#!/usr/bin/env python3
"""Turn the cluster test's stream dumps in a day log back into H.264 files and pictures.

Usage: python3 aiways/cluster_dump.py headunit-YYYY-MM-DD.log [out_dir]

Each "[cluster] dump <id> part i/N base64=..." series becomes <out_dir>/cluster-<id>.h264.
If ffmpeg is installed, every frame is also written as <out_dir>/cluster-<id>-NN.png.
"""
import base64
import os
import re
import shutil
import subprocess
import sys

PART = re.compile(r"\[cluster\] dump (\d+) part (\d+)/(\d+) base64=(\S+)")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    log, out = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else ".")
    os.makedirs(out, exist_ok=True)
    dumps = {}
    with open(log, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = PART.search(line)
            if m:
                dump_id, index, total, data = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
                dumps.setdefault(dump_id, {"total": total, "parts": {}})["parts"][index] = data
    if not dumps:
        sys.exit("no [cluster] dump lines in " + log)
    for dump_id, d in sorted(dumps.items()):
        missing = [i for i in range(1, d["total"] + 1) if i not in d["parts"]]
        if missing:
            print(f"dump {dump_id}: parts {missing} missing, skipped")
            continue
        raw = base64.b64decode("".join(d["parts"][i] for i in range(1, d["total"] + 1)))
        path = os.path.join(out, f"cluster-{dump_id}.h264")
        with open(path, "wb") as f:
            f.write(raw)
        print(f"dump {dump_id}: {len(raw)} B -> {path}")
        if shutil.which("ffmpeg"):
            pattern = os.path.join(out, f"cluster-{dump_id}-%02d.png")
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", path, pattern], check=False)
            print(f"  frames -> {pattern}")


if __name__ == "__main__":
    main()
