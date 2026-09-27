#!/usr/bin/env python3
"""
Spike S-G helper: MSPT per harness phase reconstructed from the server log.
Phases are delimited by the harness's `hywmill perf reset` / `hywmill perf` pairs; every
`/tick query` block (average + P50/P95/P99 over the last 100 ticks) between them is aggregated.

    python3 devtools/sg_mspt_from_log.py <server log> [start-marker-regex]
"""
import re
import sys


def main(path, start=None):
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    if start:
        idx = max(i for i, l in enumerate(lines) if re.search(start, l))
        lines = lines[idx:]
    phases, cur, t0 = [], None, None
    for i, l in enumerate(lines):
        ts = re.match(r"\[(\d\d:\d\d:\d\d)\]", l)
        if "hywmill perf counters reset" in l:
            cur = {"start": ts[1] if ts else "?", "avg": [], "p99": [], "p95": []}
            continue
        if cur is None:
            continue
        m = re.match(r"Average time per tick: ([\d.]+)ms", l)
        if m:
            cur["avg"].append(float(m[1]))
        m = re.search(r"P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms", l)
        if m:
            cur["p95"].append(float(m[2]))
            cur["p99"].append(float(m[3]))
        if "hywmill perf (per server" in l:
            cur["end"] = ts[1] if ts else "?"
            phases.append(cur)
            cur = None
    if cur and cur["avg"]:
        cur["end"] = "(open)"
        phases.append(cur)
    for p in phases:
        a, q = p["avg"], p["p99"]
        if not a:
            print(f"{p['start']}-{p['end']}: no tick samples")
            continue
        print(f"{p['start']}-{p['end']}: n={len(a)} MSPT mean {sum(a)/len(a):.1f} ms (min {min(a):.1f}, max {max(a):.1f}); "
              f"P95 mean {sum(p['p95'])/len(p['p95']):.1f}; P99 mean {sum(q)/len(q):.1f} ms, max {max(q):.1f} ms")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
