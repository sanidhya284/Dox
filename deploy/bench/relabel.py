#!/usr/bin/env python3
"""Re-derive the outcome of every stored run summary in docs/benchmarks/ (top level only) with the
current RunClassifier, from the fields recorded in each file. Nothing is re-run. Run from the repo root:

    python3 deploy/bench/relabel.py

The first relabel moves the run-time verdict to "labelAtRun" ({outcome, reasons}); later relabels keep
it. "outcome", "reasons" and "notes" then hold the current classifier's verdict. Prints one line per
changed outcome and a count.
"""
import json
import os
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "docs", "benchmarks")


def main():
    out = subprocess.run(["./gradlew", "-q", "benchRelabel", f"-Prelabel.dir={DOCS}"],
                         cwd=os.path.join(ROOT, "backend"), check=True, capture_output=True, text=True).stdout
    verdicts = [json.loads(line) for line in out.splitlines() if line.startswith("{")]
    changed = 0
    for v in verdicts:
        path = os.path.join(DOCS, v["file"])
        with open(path) as f:
            d = json.load(f)
        at_run = d.get("labelAtRun") or {"outcome": d["outcome"], "reasons": d["reasons"]}
        rebuilt = {}
        for k, val in d.items():
            if k in ("labelAtRun", "notes"):
                continue
            if k == "outcome":
                rebuilt["outcome"] = v["outcome"]
            elif k == "reasons":
                rebuilt["reasons"] = v["reasons"]
                rebuilt["notes"] = v["notes"]
                rebuilt["labelAtRun"] = at_run
            else:
                rebuilt[k] = val
        with open(path, "w") as f:
            json.dump(rebuilt, f, indent=1)
        if v["outcome"] != at_run["outcome"]:
            changed += 1
            print(f"{v['file']}: {at_run['outcome']} -> {v['outcome']} {v['reasons'] or v['notes']}")
    print(f"relabelled {len(verdicts)} summaries; {changed} outcomes differ from labelAtRun")


if __name__ == "__main__":
    main()
