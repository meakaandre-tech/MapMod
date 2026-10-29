#!/usr/bin/env python3
"""Checks the smoke run's logs: every selftest suite ran to its end with no failed check, and every screenshot is there.

Usage: check-selftests.py <ci dir>   (the folder with server.log, server2.log, client.log and client-NN.png)
Prints the totals per suite and every FAIL line; exits 1 if anything is missing or failed.
"""
import os
import re
import sys

SCREENSHOTS = 6
# suite prefix -> (log file, the line that says it ran to its end)
SUITES = {
    "SELFTEST map ": ("server.log", "SELFTEST map done (later checks)"),
    "SELFTEST smoke ": ("server.log", "SELFTEST smoke stamp done."),
    "SELFTEST client ": ("client.log", "SELFTEST client done locked"),
    "SELFTEST restart ": ("server2.log", "SELFTEST restart done"),
    # the client on a server with no MapMod: it keeps its own map
    "SELFTEST local ": ("client-local.log", "SELFTEST local done"),
}


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return None


def main():
    ci = sys.argv[1]
    bad = []
    total_pass = total_fail = 0
    for prefix, (log, end) in SUITES.items():
        lines = read(os.path.join(ci, log))
        if lines is None:
            bad.append(f"{log} is missing")
            continue
        # the server echoes command feedback as "System chat: ..." lines too: each check is counted once
        lines = [l for l in lines if "System chat:" not in l]
        passed = sum(1 for l in lines if prefix + "PASS " in l)
        failed = [l for l in lines if prefix + "FAIL " in l]
        total_pass += passed
        total_fail += len(failed)
        finished = any(end in l for l in lines)
        print(f"{prefix.strip()}: {passed} passed, {len(failed)} failed{'' if finished else ', DID NOT FINISH'}")
        for l in failed:
            print("  " + l[l.index(prefix):])
        if failed:
            bad.append(f"{prefix.strip()} has failed checks")
        if not finished:
            bad.append(f"{prefix.strip()} did not finish ({end!r} not in {log})")
    shots = sorted(f for f in os.listdir(ci) if re.fullmatch(r"client-\d\d\.png", f)) if os.path.isdir(ci) else []
    print(f"screenshots: {len(shots)} ({', '.join(shots)})")
    if len(shots) < SCREENSHOTS:
        bad.append(f"{len(shots)} screenshots, {SCREENSHOTS} expected")
    local = sorted(f for f in os.listdir(ci) if re.fullmatch(r"local-\d\d\.png", f)) if os.path.isdir(ci) else []
    print(f"screenshots on a server without MapMod: {len(local)} ({', '.join(local)})")
    if len(local) < 2:
        bad.append(f"{len(local)} screenshots on a server without MapMod, 2 expected")
    for log in ("server.log", "server2.log", "client.log", "client-local.log"):
        lines = read(os.path.join(ci, log)) or []
        # a stack trace through the map's code is a failure, even where no check noticed it
        traces = [i for i, l in enumerate(lines) if re.match(r"\s+at com\.meakaandre\.mapmod\.", l)]
        if traces:
            print(f"{log}: {len(traces)} stack frames in the map's code, first near line {traces[0] + 1}:")
            for l in lines[max(0, traces[0] - 6):traces[0] + 2]:
                print("  " + l)
            bad.append(f"stack traces through the map's code in {log}")
    print(f"total: {total_pass} passed, {total_fail} failed")
    if bad:
        print("NOT GREEN: " + "; ".join(bad))
        sys.exit(1)
    print("green")


if __name__ == "__main__":
    main()
