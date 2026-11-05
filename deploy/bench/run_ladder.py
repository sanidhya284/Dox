#!/usr/bin/env python3
"""Unattended Phase 5-lite measurement run (docs/BENCHMARKS.md). Run from the repo root:

    caffeinate -dims python3 deploy/bench/run_ladder.py            # run everything
    python3 deploy/bench/run_ladder.py --dry-run                    # print the plan and estimate only

Per block: stop the app, reset the BENCH database (deploy/bench/bench-db.sh; never the dev one), start
the app (bench profile, given Hikari pool), then 3 passes over the rate ladder (interleaved: rate drift
over time does not land on one point). An INVALID run is retried at most twice. Prints one line per
run; raw results go to bench-results/ (git-ignored), a compact summary per run to docs/benchmarks/.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BACKEND = os.path.join(ROOT, "backend")
RAW = os.path.join(ROOT, "bench-results")
DOCS = os.path.join(ROOT, "docs", "benchmarks")
JAR = os.path.join(BACKEND, "build", "libs", "chatplatform-backend-0.1.0-SNAPSHOT.jar")

WARMUP, WINDOW, DRAIN = 30, 60, 15
KNEE_ACK_TIMEOUT = "PT10M"          # far above warm-up + window + drain: no client retries amplify overload
DEFAULT_ACK_TIMEOUT = "PT5S"        # ReferenceClient production default, for the one comparison run
PASSES, MAX_RETRIES = 3, 2
AB_RATES = [25, 50, 100, 150, 200, 300]
C_RATES = [2, 5, 10, 20]
BLOCKS = [  # name, scenario, Hikari pool, rates
    ("A-pool10", "A", 10, AB_RATES),
    ("B-pool10", "B", 10, AB_RATES),
    ("A-pool30", "A", 30, AB_RATES),
    ("C-pool10", "C", 10, C_RATES),
]


def estimate():
    per_run = 15 + WARMUP + WINDOW + DRAIN            # gradle + setup + quiet wait + stable-row wait
    runs, secs = 0, 0
    for name, sc, pool, rates in BLOCKS:
        for r in rates:
            runs += PASSES
            extra = 60 if (sc != "C" and r >= 150) else 0  # backlog drain after overloaded runs
            secs += PASSES * (per_run + extra + (10 if sc == "C" else 0))
        secs += 45                                       # reset + app start
    runs += 1
    secs += per_run + 60                                 # the 5 s ack-timeout comparison run
    return runs, secs


def plan_text():
    lines = []
    for name, sc, pool, rates in BLOCKS:
        lines.append(f"  {name}: scenario {sc}, Hikari pool {pool}, rates {rates}/s x {PASSES} passes, "
                     f"ackTimeout {KNEE_ACK_TIMEOUT}")
    lines.append(f"  A-ack5s: scenario A, pool 10, ackTimeout {DEFAULT_ACK_TIMEOUT}, 1 run at the lowest A-pool10 "
                 f"rate whose runs were mostly SATURATED (else the top rate)")
    runs, secs = estimate()
    lines.append(f"  each run: warm-up {WARMUP}s + window {WINDOW}s + drain <= {DRAIN}s (+ setup, quiet wait, "
                 f"invariant check); INVALID retried <= {MAX_RETRIES}x (not in the estimate)")
    lines.append(f"  total: {runs} runs, estimated {secs / 3600:.2f} h")
    return "\n".join(lines)


def sh(cmd, **kw):
    return subprocess.run(cmd, cwd=ROOT, **kw)


def healthy():
    try:
        with urllib.request.urlopen("http://localhost:8080/actuator/health", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False


APP = {"proc": None}


def stop_app():
    p = APP["proc"]
    if p is not None:
        p.terminate()
        try:
            p.wait(30)
        except subprocess.TimeoutExpired:
            p.kill()
        APP["proc"] = None
    deadline = time.time() + 30
    while healthy() and time.time() < deadline:
        time.sleep(1)


def start_app(pool, block):
    env = dict(os.environ, SPRING_PROFILES_ACTIVE="bench", BENCH_HIKARI_POOL=str(pool))
    log = open(os.path.join(RAW, f"app-{block}.log"), "w")
    APP["proc"] = subprocess.Popen(["java", "-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-jar", JAR],
                                   cwd=BACKEND, env=env, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.time() + 90
    while not healthy():
        if time.time() > deadline or APP["proc"].poll() is not None:
            raise SystemExit(f"app did not start for {block}; see bench-results/app-{block}.log")
        time.sleep(1)


def compact(raw):
    env = raw.get("environment", {})
    keep_env = {k: env.get(k) for k in ["cpu", "logicalCpus", "memBytes", "os", "power", "thermal", "dockerVm",
                                        "dockerNote", "runningContainers", "benchContainerLimits", "gitCommit",
                                        "gitDirtyFiles", "harnessJvm", "appCommandLine", "postgres",
                                        "appEffectiveConfig"]}
    return {
        "scenario": raw["scenario"], "label": raw["label"], "runTag": raw["runTag"], "startedAt": raw["startedAt"],
        "outcome": raw["outcome"], "reasons": raw["reasons"], "notes": raw.get("notes", []),
        "params": raw["params"], "shape": raw["shape"],
        "setupSeconds": raw["setupSeconds"], "sends": raw["sends"], "latencyMs": raw["latencyMs"],
        "harnessGcShare": raw["harnessGcShare"], "server": raw["server"],
        "serverCounterDeltas": raw["serverCounterDeltas"], "receiverCloseCodes": raw["receiverCloseCodes"],
        "invariants": raw["invariants"], "samplerErrors": len(raw["samplerErrors"]), "environment": keep_env,
        "rawSamplesFile": "bench-results/" + raw["_file"] + " (git-ignored)",
    }


def run_one(scenario, rate, label, ack_timeout, counter):
    args = (f"scenario={scenario} rate={rate} warmup=PT{WARMUP}S window=PT{WINDOW}S drain=PT{DRAIN}S "
            f"ackTimeout={ack_timeout} label={label} out={RAW}")
    log_path = os.path.join(RAW, f"run-{label}-r{rate}.log")
    with open(log_path, "w") as log:
        code = subprocess.run(["./gradlew", "-q", "loadTest", f"-Pload.args={args}"], cwd=BACKEND,
                              stdout=log, stderr=subprocess.STDOUT).returncode
    summary = next((l.strip() for l in open(log_path) if l.startswith(scenario + " rate=")), None)
    if summary is None:
        print(f"[{counter}] {label} r{rate}: NO RESULT (exit {code}); see {os.path.relpath(log_path, ROOT)}",
              flush=True)
        return {"label": label, "rate": rate, "outcome": "NO_RESULT", "exit": code}
    path = summary.rsplit("| ", 1)[1].strip()
    raw = json.load(open(path))
    raw["_file"] = os.path.basename(path)
    out = os.path.join(DOCS, os.path.basename(path))
    with open(out, "w") as f:
        json.dump(compact(raw), f, indent=1)
    print(f"[{counter}] {summary.rsplit(' | ', 1)[0]} | {os.path.relpath(out, ROOT)}", flush=True)
    return {"label": label, "rate": rate, "outcome": raw["outcome"], "file": os.path.relpath(out, ROOT),
            "exit": code}


def run_point(scenario, rate, label, ack_timeout, counter, index):
    for attempt in range(MAX_RETRIES + 1):
        lab = label if attempt == 0 else f"{label}-retry{attempt}"
        r = run_one(scenario, rate, lab, ack_timeout, counter)
        r.update(scenario=scenario, attempt=attempt, block=label.rsplit("-p", 1)[0])
        index.append(r)
        with open(os.path.join(RAW, "index.json"), "w") as f:
            json.dump(index, f, indent=1)
        if r["outcome"] != "INVALID":
            return r
    return r


def main():
    print("RUN PLAN\n" + plan_text(), flush=True)
    if "--dry-run" in sys.argv:
        return
    if subprocess.run(["docker", "info"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0:
        raise SystemExit("Docker is not reachable: start Docker Desktop first")
    os.makedirs(RAW, exist_ok=True)
    os.makedirs(DOCS, exist_ok=True)
    if subprocess.run(["./gradlew", "-q", "bootJar", "testClasses"], cwd=BACKEND).returncode != 0:
        raise SystemExit("build failed")
    index, n = [], 0
    started = time.time()
    try:
        for block, scenario, pool, rates in BLOCKS:
            stop_app()
            sh(["deploy/bench/bench-db.sh", "reset"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
               check=True)
            start_app(pool, block)
            for p in range(1, PASSES + 1):
                for rate in rates:
                    n += 1
                    r = run_point(scenario, rate, f"{block}-p{p}", KNEE_ACK_TIMEOUT, n, index)
                    if r["outcome"] == "INVARIANT_VIOLATION":
                        raise SystemExit(f"stopping: INVARIANT_VIOLATION in {block} r{rate} (a bug, not a result)")
                    if r["outcome"] == "NO_RESULT":
                        docker_ok = subprocess.run(["docker", "info"], stdout=subprocess.DEVNULL,
                                                   stderr=subprocess.DEVNULL).returncode == 0
                        raise SystemExit(f"stopping: no result in {block} r{rate} (harness or environment failure; "
                                         f"Docker reachable: {docker_ok}); see its run log")
            if block == "A-pool10":
                sat = [rt for rt in rates if sum(1 for x in index if x.get("block") == block and x["rate"] == rt
                                                 and x["outcome"] == "SATURATED") >= 2]
                rate = sat[0] if sat else rates[-1]
                n += 1
                run_point("A", rate, "A-ack5s-p1", DEFAULT_ACK_TIMEOUT, n, index)
    finally:
        stop_app()
    print(f"DONE: {n} points, {len(index)} runs (incl. retries) in {(time.time() - started) / 3600:.2f} h; "
          f"index: bench-results/index.json", flush=True)


if __name__ == "__main__":
    main()
