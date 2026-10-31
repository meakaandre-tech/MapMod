#!/usr/bin/env python3
"""Checks the smoke run's logs: every selftest suite ran to its end with no failed check, and every screenshot is there.

Usage: check-selftests.py <ci dir>   (the folder with server.log, server2.log, client.log and client-NN.png)
Prints the totals per suite and every FAIL line; exits 1 if anything is missing or failed.
"""
import os
import re
import sys

SCREENSHOTS = 11
# on the server without MapMod: ready, done, the patch close up / at a pixel a block / far, replaced, the resource pack,
# GUI scales 3 and 4
LOCAL_SCREENSHOTS = 9
# suite prefix -> (log file, the line that says it ran to its end)
SUITES = {
    "SELFTEST map ": ("server.log", "SELFTEST map done (later checks)"),
    "SELFTEST smoke ": ("server.log", "SELFTEST smoke stamp done."),
    "SELFTEST client ": ("client.log", "SELFTEST client done locked"),
    "SELFTEST restart ": ("server2.log", "SELFTEST restart done"),
    # the client on a server with no MapMod: it keeps its own map
    "SELFTEST local ": ("client-local.log", "SELFTEST local done"),
    # MM-7/MM-8: the patch's five strips paint in their real colours, the map follows a block replaced on the server and
    # a resource pack that changes a texture
    "SELFTEST colours ": ("client-local.log", "SELFTEST colours done"),
    # MM-6: the public API, used the way another mod would (and its effects inside the map)
    "SELFTEST api ": ("client.log", "SELFTEST api done after reload"),
}


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return None


# MM-8: the patch's strips (west to east, 16 blocks wide each, centred on these x) and what each must look like on screen
STRIPS = [("mycelium", -32), ("grass", -16), ("sand", 0), ("stone", 16), ("terracotta", 32)]
# the band of rows the screenshots are read along: clear of the mobs, markers and the player on the patch
BAND = (-26, -18)


def looks(name, c):
    r, g, b = c
    if name == "mycelium":
        return r >= g + 4 and b >= g + 2 and max(c) - min(c) <= 48
    if name == "grass":
        return g >= r + 12 and g >= b + 12
    if name == "sand":
        return r >= b + 25 and g >= b + 15 and r + g + b >= 400
    if name == "stone":
        return max(c) - min(c) <= 24
    return r >= g + 30 and g >= b + 10


def geometry(ci):
    """The screen geometry the colour tests logged for each patch shot: x of world x 0, y of world z 0, pixels a block."""
    out = {}
    for l in read(os.path.join(ci, "client-local.log")) or []:
        m = re.search(r"SELFTEST colours geometry (\S+) x0 ([-\d.]+) z0 ([-\d.]+) ppb ([\d.]+)", l)
        if m:
            out[m.group(1)] = (float(m.group(2)), float(m.group(3)), float(m.group(4)))
    return out


def patch(ci, Image, bad):
    """
    The patch on screen (MM-8): each strip in its real colour, close up and at a pixel a block; close up, each strip a
    soft area with no block grid in it, and each border between two strips sharp.
    """
    geo = geometry(ci)
    for shot, name in (("local-03.png", "patch"), ("local-04.png", "patch-mid")):
        path = os.path.join(ci, shot)
        if name not in geo or not os.path.exists(path):
            bad.append(f"no {name} screenshot or geometry to read the patch's colours from")
            continue
        x0, z0, ppb = geo[name]
        im = Image.open(path).convert("RGB")
        px, (w, h) = im.load(), im.size

        def at(wx, wz):
            x, y = int(round(x0 + wx * ppb)), int(round(z0 + wz * ppb))
            return px[min(max(x, 0), w - 1), min(max(y, 0), h - 1)]

        def mean(wx, wz, r):
            cs = [at(wx + dx * 0.5, wz + dz * 0.5) for dx in range(-2 * r, 2 * r + 1) for dz in range(-2 * r, 2 * r + 1)]
            return tuple(round(sum(c[i] for c in cs) / len(cs)) for i in range(3))

        means = {}
        for strip, cx in STRIPS:
            c = mean(cx, (BAND[0] + BAND[1]) / 2, 3)
            means[strip] = c
            ok = looks(strip, c)
            print(f"{shot} ({name}): {strip} #{c[0]:02X}{c[1]:02X}{c[2]:02X} {'ok' if ok else 'WRONG'}")
            if not ok:
                bad.append(f"{strip} is not its real colour in {shot}")
        if name != "patch":
            continue
        # no block grid: inside each strip, the steps between neighbouring pixels along a row are small and do not come
        # back every block or every sample (a grid would put its steps at the same place in each)
        step = max(1, int(round(ppb)))
        for strip, cx in STRIPS:
            xa, xb = int(x0 + (cx - 5) * ppb), int(x0 + (cx + 5) * ppb)
            ya, yb = int(z0 + BAND[0] * ppb), int(z0 + BAND[1] * ppb)
            phase = [[] for _ in range(2 * step)]
            for y in range(ya, yb):
                for x in range(xa + 1, xb):
                    a, b2 = px[x - 1, y], px[x, y]
                    phase[(x - int(x0)) % (2 * step)].append(sum(abs(a[i] - b2[i]) for i in range(3)))
            means_p = [sum(p) / len(p) for p in phase if p]
            overall = sum(means_p) / len(means_p)
            worst = max(means_p)
            grid = worst > 2 * overall + 3
            print(f"{shot}: {strip} pixel steps {overall:.1f} on average, {worst:.1f} at worst of {2 * step} phases{' GRID' if grid else ''}")
            if grid:
                bad.append(f"a block grid shows in the {strip} strip in {shot}")
        # sharp borders: along each row, the move from one strip's colour to the next's happens within 2.5 blocks
        for (sa, ca), (sb, cb) in zip(STRIPS, STRIPS[1:]):
            A, B = means[sa], means[sb]
            d2 = sum((B[i] - A[i]) ** 2 for i in range(3)) or 1
            widths = []
            for y in range(int(z0 + BAND[0] * ppb), int(z0 + BAND[1] * ppb), 2):
                n = 0
                for x in range(int(x0 + (ca + 3) * ppb), int(x0 + (cb - 3) * ppb)):
                    p = px[x, y]
                    t = sum((p[i] - A[i]) * (B[i] - A[i]) for i in range(3)) / d2
                    if 0.15 < t < 0.85:
                        n += 1
                widths.append(n / ppb)
            widths.sort()
            med = widths[len(widths) // 2] if widths else 99
            print(f"{shot}: the {sa}/{sb} border is {med:.1f} blocks wide (median of {len(widths)} rows)")
            if med > 2.5:
                bad.append(f"the {sa}/{sb} border is soft ({med:.1f} blocks) in {shot}")


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
    # the client's later suites (MM-4) end with lines of their own
    client = [l for l in (read(os.path.join(ci, "client.log")) or []) if "System chat:" not in l]
    # and MM-5's
    for end in ("SELFTEST client done bake", "SELFTEST client done far", "SELFTEST client done unload",
                "SELFTEST client done fog", "SELFTEST client done overlays", "SELFTEST client done dimension",
                "SELFTEST client done ownfog"):
        if not any(end in l for l in client):
            print(f"client: {end!r} missing, DID NOT FINISH")
            bad.append(f"{end!r} not in client.log")
    shots = sorted(f for f in os.listdir(ci) if re.fullmatch(r"client-\d\d\.png", f)) if os.path.isdir(ci) else []
    print(f"screenshots: {len(shots)} ({', '.join(shots)})")
    if len(shots) < SCREENSHOTS:
        bad.append(f"{len(shots)} screenshots, {SCREENSHOTS} expected")
    local = sorted(f for f in os.listdir(ci) if re.fullmatch(r"local-\d\d\.png", f)) if os.path.isdir(ci) else []
    print(f"screenshots on a server without MapMod: {len(local)} ({', '.join(local)})")
    if len(local) < LOCAL_SCREENSHOTS:
        bad.append(f"{len(local)} screenshots on a server without MapMod, {LOCAL_SCREENSHOTS} expected")
    # the painted map's tiles meet exactly: no line of the paper colour between two of them
    try:
        from PIL import Image
    except ImportError:
        Image = None
        bad.append("Pillow is missing, so the tile-gap check could not run")
    # 02 level 2, 03 live, 04 close, 05 far (a large made-up area, level 3), 06 level 1, 07 level 0 baked, 08 filters
    for shot in ("client-02.png", "client-03.png", "client-04.png", "client-05.png", "client-06.png", "client-07.png", "client-08.png",
                 "local-02.png", "local-03.png", "local-04.png", "local-05.png"):
        if Image is None or not os.path.exists(os.path.join(ci, shot)):
            continue
        im = Image.open(os.path.join(ci, shot)).convert("RGB")
        px, (w, h) = im.load(), im.size
        # a gap is a line of paper (unexplored land, and the background where no tile is drawn) between two pixels of
        # land. The paper carries a grain and a vignette that scale it evenly (MM-3), so "paper" is any pixel that is the
        # paper colour times one brightness, within rounding; no land colour is (sand and snow differ in hue). The fog
        # (MM-5) is the paper a shade darker with paper clouds in it, so it counts as paper too
        paper = (0xE6, 0xDC, 0xC6)
        psum = sum(paper)

        def papery(p):
            k = sum(p) / psum
            return 0.7 <= k <= 1.15 and all(abs(p[i] - k * paper[i]) <= 6 for i in range(3))

        def gap(a, b, c):
            return papery(a) and not papery(b) and not papery(c)
        gaps = [x for x in range(285, w - 1) if sum(1 for y in range(40, h - 20) if gap(px[x, y], px[x - 1, y], px[x + 1, y])) > 30]
        gaps += [y for y in range(40, h - 21) if sum(1 for x in range(285, w - 1) if gap(px[x, y], px[x, y - 1], px[x, y + 1])) > 30]
        print(f"{shot}: {len(gaps)} lines of paper between tiles{' at ' + str(gaps) if gaps else ''}")
        if gaps:
            bad.append(f"lines between map tiles in {shot}")
    if Image is not None:
        patch(ci, Image, bad)
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
