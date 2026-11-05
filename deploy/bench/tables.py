#!/usr/bin/env python3
"""Regenerate the result tables in docs/BENCHMARKS.md (between the "tables:start" and "tables:end"
markers) from the stored summaries in docs/benchmarks/ (top level). Run from the repo root:

    python3 deploy/bench/tables.py

Each cell is the median [min-max] over a point's runs, excluding INVALID ones (current label).
"""
import glob
import json
import os
import re
import statistics as st

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "docs")
BLOCKS = [
    ("A-pool10", "A: hot conversation (10 senders, 1 group), Hikari pool 10"),
    ("A-pool30", "A: hot conversation, Hikari pool 30"),
    ("B-pool10", "B: control (same 10 senders, 10 groups), Hikari pool 10"),
]


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def spread(vals, nd=1):
    v = [num(x) for x in vals if num(x) is not None]
    if not v:
        return "n/a"
    if len(v) == 1:
        return f"{v[0]:.{nd}f}"
    return f"{st.median(v):.{nd}f} [{min(v):.{nd}f}–{max(v):.{nd}f}]"


def load():
    runs = []
    for path in sorted(glob.glob(os.path.join(DOCS, "benchmarks", "*.json"))):
        d = json.load(open(path))
        d["_file"] = "benchmarks/" + os.path.basename(path)
        d["_block"] = re.sub(r"-p\d+(-retry\d+)?$", "", d["label"])
        d["_pass"] = re.search(r"-(p\d+(-retry\d+)?)$", d["label"]).group(1)
        runs.append(d)
    points = {}
    for d in runs:
        points.setdefault(d["_block"], {}).setdefault(int(d["params"]["targetRate"]), []).append(d)
    return points


def outcomes(rs):
    counts = {}
    for d in rs:
        counts[d["outcome"]] = counts.get(d["outcome"], 0) + 1
    text = ", ".join(f"{v} {k}" for k, v in counts.items())
    changed = sum(1 for d in rs if d.get("labelAtRun", {}).get("outcome", d["outcome"]) != d["outcome"])
    return text + (f" ({changed} relabelled†)" if changed else "")


def links(rs):
    return " ".join(f"[{d['_pass']}]({d['_file']})" for d in rs)


def ab_table(points, block, title):
    out = [f"### {title}", "",
           "| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s "
           "| Hikari pending s | Lock share | Runs |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for rate, rs in sorted(points[block].items()):
        use = [d for d in rs if d["outcome"] != "INVALID"]
        tot = lock = 0
        for d in use:
            for k, v in d["server"]["pgActivityConnectionSamples"].items():
                if k.startswith("active"):
                    tot += v
                    lock += v if "Lock:" in k else 0
        ns = ",".join(str(n) for n in sorted({d["latencyMs"]["sendToAck"]["count"] for d in use}))
        ack = lambda q: [d["latencyMs"]["sendToAck"][q] for d in use]
        out.append(
            f"| {rate} | {outcomes(rs)} | {ns} | {spread(ack('p50'))} | {spread(ack('p99'))} | {spread(ack('max'))} "
            f"| {spread([d['latencyMs']['sendToReceive']['p99'] for d in use])} "
            f"| {spread([d['serverCounterDeltas']['messagesCreated'] / 60 for d in use])} "
            f"| {'/'.join(str(d['server']['hikariPendingSamples']) for d in use)} "
            f"| {lock}/{tot} ({100 * lock / max(tot, 1):.0f}%) | {links(rs)} |")
    return out


def c_table(points):
    out = ["### C: fan-out (1 sender, 200 receive-only sockets, so 200 pushes per message)", "",
           "| Rate/s | Outcomes | n acks/run | n receives/run | ack p50 | recv p50 | recv p99 | recv max "
           "| Pushes dropped | 4003 closes | Push queue max | Runs |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for rate, rs in sorted(points["C-pool10"].items()):
        use = [d for d in rs if d["outcome"] != "INVALID"]
        recv = lambda q: [d["latencyMs"]["sendToReceive"][q] for d in use]
        out.append(
            f"| {rate} | {outcomes(rs)} | {use[0]['latencyMs']['sendToAck']['count']} "
            f"| {use[0]['latencyMs']['sendToReceive']['count']} "
            f"| {spread([d['latencyMs']['sendToAck']['p50'] for d in use])} | {spread(recv('p50'))} "
            f"| {spread(recv('p99'))} | {spread(recv('max'))} "
            f"| {sum(d['serverCounterDeltas']['pushDropped'] for d in use):.0f} "
            f"| {sum(d['serverCounterDeltas']['slowConsumerCloses4003'] for d in use):.0f} "
            f"| {max(d['server']['pushQueuedMax'] for d in use):.0f} | {links(rs)} |")
    return out


def main():
    points = load()
    lines = []
    for block, title in BLOCKS:
        lines += ab_table(points, block, title) + [""]
    lines += c_table(points)
    lines += ["", "† Outcome re-derived after the run by the current classifier from the recorded fields "
                  "(see \"Relabelling\" above); the run-time label is kept in the file as `labelAtRun`."]
    path = os.path.join(DOCS, "BENCHMARKS.md")
    src = open(path).read()
    start, end = "<!-- tables:start (deploy/bench/tables.py) -->", "<!-- tables:end -->"
    a, b = src.index(start) + len(start), src.index(end)
    open(path, "w").write(src[:a] + "\n" + "\n".join(lines) + "\n" + src[b:])
    print("tables regenerated")


if __name__ == "__main__":
    main()
