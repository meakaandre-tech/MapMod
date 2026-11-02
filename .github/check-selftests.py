#!/usr/bin/env python3
"""Checks the smoke run's logs: every selftest suite ran to its end with no failed check, and every screenshot is there.

Usage: check-selftests.py <ci dir>   (the folder with server.log, server2.log, client.log and client-NN.png)
Prints the totals per suite and every FAIL line; exits 1 if anything is missing or failed.
"""
import math
import os
import re
import sys

SCREENSHOTS = 12
# on the server without MapMod: ready, done, the patch close up / at a pixel a block / far, replaced, the resource pack,
# GUI scales 3 and 4, and unexplored land close up / far / farther (MM-8c)
LOCAL_SCREENSHOTS = 12
# suite prefix -> (log file, the line that says it ran to its end)
SUITES = {
    "SELFTEST map ": ("server.log", "SELFTEST map done (later checks)"),
    "SELFTEST smoke ": ("server.log", "SELFTEST smoke stamp done."),
    "SELFTEST client ": ("client.log", "SELFTEST client done locked"),
    "SELFTEST restart ": ("server2.log", "SELFTEST restart done"),
    # the client on a server with no MapMod: it keeps its own map
    "SELFTEST local ": ("client-local.log", "SELFTEST local done"),
    # MM-7/MM-8: the patch's five strips show in their real colours, the map follows a block replaced on the server and
    # a resource pack that changes a texture
    "SELFTEST colours ": ("client-local.log", "SELFTEST colours done"),
    # MM-6: the public API, used the way another mod would (and its effects inside the map)
    "SELFTEST api ": ("client.log", "SELFTEST api done after reload"),
    # MM-8c: unexplored land at three zooms, on a server without MapMod (the shots are read by fog() below)
    "SELFTEST fogshots ": ("client-local.log", "SELFTEST fogshots done"),
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
# MM-8b: the rows where the borders between strips are straight (clear of the strays below), and the two staircases
# (z 22-39: the sand/stone border a block further west every 2 rows; z 24-39: the grass/sand border 2 blocks every 4)
STRAIGHT = (-38, -16)
STAIRS = (23, 38)
BORDERS = {
    ("mycelium", "grass"): lambda z: -24,
    ("grass", "sand"): lambda z: -8 if z < 24 else -8 - 2 * ((int(z) - 20) // 4),
    ("sand", "stone"): lambda z: 8 if z < 22 else 8 - (int(z) - 20) // 2,
    ("stone", "terracotta"): lambda z: 24,
}
# MM-8b: stray blocks inside strips (block centres; two side by side in the mycelium; a poppy on the grass)
STRAYS = [(-32.0, -11.5, "mycelium"), (-17.5, -11.5, "grass"), (-13.5, -7.5, "grass"), (-19.5, -13.5, "grass"),
          (2.5, -13.5, "sand"), (18.5, -11.5, "stone"), (32.5, -9.5, "terracotta")]


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


def trace(px, w, x0, z0, ppb, A, B, za, zb, expect):
    """
    The border from colour A (west) to colour B (east) along each pixel row from world z za to zb: the world x where the
    row first passes half way from A to B, near where the blocks put it (within 7 blocks).
    """
    d2 = sum((B[i] - A[i]) ** 2 for i in range(3)) or 1
    out = []
    for y in range(int(math.ceil(z0 + za * ppb)), int(z0 + zb * ppb) + 1):
        z = (y - z0) / ppb
        xe = expect(z)
        xa, xb = max(1, int(x0 + (xe - 7) * ppb)), min(w - 1, int(x0 + (xe + 7) * ppb) + 1)
        ts = [sum((px[x, y][i] - A[i]) * (B[i] - A[i]) for i in range(3)) / d2 for x in range(xa, xb)]
        # a crossing with a block of A's side before it and of B's after (so an odd dot over the map is not one)
        k = max(1, int(round(ppb)))
        for j in range(k, len(ts) - k + 1):
            if ts[j - 1] < 0.5 <= ts[j] and all(t < 0.5 for t in ts[j - k:j]) and all(t >= 0.5 for t in ts[j:j + k]):
                out.append((z, (xa + j - 1 + (0.5 - ts[j - 1]) / (ts[j] - ts[j - 1]) - x0) / ppb))
                break
    return out


def patch(ci, Image, bad):
    """
    The patch on screen (MM-8, MM-9): each strip in its real colour at every zoom. Close up (6 pixels a block) every block
    is a crisp square of one colour: the borders between strips follow the blocks exactly, staircases included, and are
    a pixel wide at most; the stray blocks show as single blocks of their own colour (the poppy, a plant, does not: it is
    looked past); the patch's north edge, higher than the land north of it, is lit by the height shading. At a pixel a
    block the borders and strays are where the blocks put them. Far out (4 blocks a pixel) each strip is an even average.
    """
    geo = geometry(ci)
    for shot, name in (("local-03.png", "patch"), ("local-04.png", "patch-mid"), ("local-05.png", "patch-far")):
        path = os.path.join(ci, shot)
        if name not in geo or not os.path.exists(path):
            bad.append(f"no {name} screenshot or geometry to read the patch's colours from")
            continue
        x0, z0, ppb = geo[name]
        im = Image.open(path).convert("RGB")
        px, (w, h) = im.load(), im.size

        def at(wx, wz):
            # the pixel the world point lies in: close up a block is crisp, so this is the block's colour
            x, y = int(math.floor(x0 + wx * ppb)), int(math.floor(z0 + wz * ppb))
            return px[min(max(x, 0), w - 1), min(max(y, 0), h - 1)]

        def mean(wx, wz, r):
            cs = [at(wx + dx * 0.5, wz + dz * 0.5) for dx in range(-2 * r, 2 * r + 1) for dz in range(-2 * r, 2 * r + 1)]
            return tuple(round(sum(c[i] for c in cs) / len(cs)) for i in range(3))

        def off(a, b):
            return sum(abs(a[i] - b[i]) for i in range(3))

        means = {}
        for strip, cx in STRIPS:
            c = mean(cx, (BAND[0] + BAND[1]) / 2, 3)
            means[strip] = c
            ok = looks(strip, c)
            print(f"{shot} ({name}): {strip} #{c[0]:02X}{c[1]:02X}{c[2]:02X} {'ok' if ok else 'WRONG'}")
            if not ok:
                bad.append(f"{strip} is not its real colour in {shot}")
        if name == "patch-far":
            # an even average: inside each strip the pixels are alike (no shimmer, no speckle); the borders are reported
            for strip, cx in STRIPS:
                cs = [at(cx + dx, (BAND[0] + BAND[1]) / 2 + dz) for dx in range(-4, 5, 2) for dz in range(-4, 5, 2)]
                worst = max(off(c, means[strip]) for c in cs)
                ok = worst <= 12
                print(f"{shot}: {strip} far out is even: {worst} at most off its mean {'ok' if ok else 'UNEVEN'}")
                if not ok:
                    bad.append(f"the {strip} strip is not an even average in {shot}")
            for (sa, sb), expect in BORDERS.items():
                pts = trace(px, w, x0, z0, ppb, means[sa], means[sb], *STRAIGHT, expect)
                if pts:
                    errs = [abs(x + 0.5 / ppb - expect(z)) for z, x in pts]
                    print(f"{shot}: the {sa}/{sb} border is {sum(errs) / len(errs):.2f} blocks off the blocks' line on average ({len(pts)} rows)")
            continue
        # the borders follow the blocks: along each pixel row the crossing is where the blocks put it, within half a pixel
        # (trace reads pixel indices, so half a pixel is added back), where the border is straight and along both staircases
        lim = max(0.25, 0.75 / ppb)
        for (sa, sb), expect in BORDERS.items():
            for part, (za, zb) in (("straight", STRAIGHT), ("stairs", STAIRS)):
                if part == "stairs" and (sa, sb) not in (("grass", "sand"), ("sand", "stone")):
                    continue
                # close up, rows inside a block, so a staircase's step is never on the row read
                pts = [(z, x) for z, x in trace(px, w, x0, z0, ppb, means[sa], means[sb], za, zb, expect)
                       if ppb < 3 or 0.2 <= (z + 0.5 / ppb) % 1 <= 0.8]
                rows = int((zb - za) * ppb * (0.55 if ppb >= 3 else 1))
                if len(pts) < 0.7 * rows:
                    print(f"{shot}: the {sa}/{sb} border ({part}) traced on {len(pts)} of {rows} rows")
                    bad.append(f"the {sa}/{sb} border ({part}) could not be traced in {shot}")
                    continue
                errs = [abs(x + 0.5 / ppb - expect(z + 0.5 / ppb)) for z, x in pts]
                worst = max(errs)
                ok = worst <= lim
                print(f"{shot}: the {sa}/{sb} border ({part}, {len(pts)} rows) is {worst:.2f} blocks off the blocks' line at most "
                      f"({sum(errs) / len(errs):.2f} on average) {'ok' if ok else 'OFF'}")
                if not ok:
                    bad.append(f"the {sa}/{sb} border ({part}) does not follow the blocks in {shot}")
        # stray blocks show as single blocks of their own colour; the poppy (a plant, looked past) does not
        for sx, sz, strip in STRAYS:
            M = means[strip]
            c = at(sx, sz)
            poppy = (sx, sz) == (-19.5, -13.5)
            if poppy:
                ok = off(c, M) <= 12
                print(f"{shot}: the poppy on the grass at {sx:g}, {sz:g} is {off(c, M)} off the grass: {'looked past, ok' if ok else 'SHOWS'}")
            else:
                # its own block differs; a block away the strip's colour is back
                beside = [at(sx + dx, sz + dz) for dx, dz in ((0, -1), (0, 1), (1.5, 0))]
                back = all(off(r, M) <= 12 for r in beside)
                ok = off(c, M) >= 40 and back
                print(f"{shot}: a stray block in the {strip} at {sx:g}, {sz:g} is {off(c, M)} off the strip; "
                      f"the blocks beside it {'are' if back else 'are NOT'} the strip's colour {'ok' if ok else 'NOT A BLOCK'}")
            if not ok:
                bad.append(f"the stray block in the {strip} at {sx:g}, {sz:g} is {'shown' if poppy else 'not shown as a block'} in {shot}")
        if name != "patch":
            continue
        # crisp squares: inside each block of the band, every pixel is the block's colour (no blur, no texture)
        blurred = total = 0
        for strip, cx in STRIPS:
            for bx in range(cx - 5, cx + 5):
                for bz in range(BAND[0], BAND[1]):
                    c = at(bx + 0.5, bz + 0.5)
                    for fx in (0.2, 0.8):
                        for fz in (0.2, 0.8):
                            total += 1
                            if off(at(bx + fx, bz + fz), c) > 6:
                                blurred += 1
        ok = blurred <= total * 0.02
        print(f"{shot}: blocks are crisp squares: {blurred} of {total} corner pixels differ from their block's middle {'ok' if ok else 'BLURRED'}")
        if not ok:
            bad.append(f"the blocks are not crisp squares in {shot}")
        # sharp borders: along each row the move from one strip's colour to the next's takes a pixel at most
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
                widths.append(n)
            widths.sort()
            med = widths[len(widths) // 2] if widths else 99
            print(f"{shot}: the {sa}/{sb} border is {med} pixels wide (median of {len(widths)} rows)")
            if med > 1:
                bad.append(f"the {sa}/{sb} border is soft ({med} pixels) in {shot}")
        # height shading: the patch's first row (z -40) stands above the land north of it, so it is lighter than the
        # rows behind it; inside, on the flat, the rows are alike
        lit = 0
        for strip, cx in STRIPS:
            edge, inner, inner2 = at(cx + 0.5, -39.5), at(cx + 0.5, -37.5), at(cx + 0.5, -35.5)
            brighter = sum(edge) - sum(inner)
            ok = brighter >= 12 and off(inner, inner2) <= 6
            lit += 1 if ok else 0
            print(f"{shot}: the patch's north edge over the {strip} is {brighter} brighter than the row behind it {'ok' if ok else 'FLAT'}")
        if lit < 4:
            bad.append(f"the patch's raised north edge is not lit (height shading) in {shot}")


# MM-8c: the 80-block patch far east of the player (FogShotTests), the land round the player, and half way between
FOG_PATCH = (8248.0, 248.0)
FOG_MID = (4104.0, 124.0)
# MM-9: unexplored land is one flat colour (map_palette.json's background)
BACKGROUND = (0x16, 0x18, 0x1D)


def fogged(p):
    """Unexplored land: the background colour, within rounding."""
    return all(abs(p[i] - BACKGROUND[i]) <= 4 for i in range(3))


def fog_geometry(ci):
    out = {}
    for l in read(os.path.join(ci, "client-local.log")) or []:
        m = re.search(r"SELFTEST fogshots geometry (\S+) x0 ([-\d.]+) z0 ([-\d.]+) ppb ([\d.]+) px (-?\d+) pz (-?\d+)", l)
        if m:
            out[m.group(1)] = tuple(float(m.group(i)) for i in range(2, 7))
    return out


def chunk_aligned(px, w, h, x0, z0, ppb, skip):
    """
    Where the land meets unexplored land (MM-8c), how much of it lies on the chunk grid: along each pixel row and column,
    every crossing between land and the background, at its world position; the share within a block of a chunk line (3 of
    every 16 blocks for an edge that ignores the grid, all for the hard chunk edges of MM-9), and how many there were.
    Crossings within skip blocks of world 0, 0 (the patch's strips, sand among them) are left out.
    """
    fog = [[fogged(px[x, y]) for x in range(w)] for y in range(h)]
    near = total = 0
    for y in range(40, h - 30):
        wz = (y + 0.5 - z0) / ppb
        for x in range(290, w - 2):
            if fog[y][x] == fog[y][x + 1]:
                continue
            wx = (x + 1 - x0) / ppb
            if abs(wx) <= skip and abs(wz) <= skip:
                continue
            total += 1
            near += 1 if (wx % 16 <= 1 or wx % 16 >= 15) else 0
    for x in range(290, w - 2):
        wx = (x + 0.5 - x0) / ppb
        for y in range(40, h - 31):
            if fog[y][x] == fog[y + 1][x]:
                continue
            wz = (y + 1 - z0) / ppb
            if abs(wx) <= skip and abs(wz) <= skip:
                continue
            total += 1
            near += 1 if (wz % 16 <= 1 or wz % 16 >= 15) else 0
    return (near / total if total else 1.0), total


def fog(ci, Image, bad):
    """
    Unexplored land at three zooms (MM-8c, MM-9): close up it is the flat background, its edge the hard edge of the chunks
    the player captured; far out (8 and 32 blocks a pixel) the land round the player and the 80-block patch far east of
    it are clearly visible, and the background lies between them.
    """
    geo = fog_geometry(ci)
    for shot, name in (("local-10.png", "fog-near"), ("local-11.png", "fog-mid"), ("local-12.png", "fog-far")):
        path = os.path.join(ci, shot)
        if name not in geo or not os.path.exists(path):
            bad.append(f"no {name} screenshot or geometry for the fog check")
            continue
        x0, z0, ppb, pwx, pwz = geo[name]
        im = Image.open(path).convert("RGB")
        px, (w, h) = im.load(), im.size

        def at(wx, wz):
            return x0 + wx * ppb, z0 + wz * ppb

        def land_share(wx, wz, r):
            cx, cy = at(wx, wz)
            n = shown = 0
            for dy in range(-r, r + 1):
                for dx in range(-r, r + 1):
                    x, y = int(round(cx + dx)), int(round(cy + dy))
                    if 285 <= x < w and 40 <= y < h - 20:
                        n += 1
                        shown += 0 if fogged(px[x, y]) else 1
            return shown, n

        # close up, the edge of the player's land is the hard edge of its chunks: its crossings lie on chunk lines
        if name == "fog-near":
            share, total = chunk_aligned(px, w, h, x0, z0, ppb, 48)
            ok = total >= 200 and share >= 0.9
            print(f"{shot} ({name}): {total} crossings between land and the background, {share:.2f} of them within a block of a chunk line "
                  f"(0.19 for an edge that ignores the grid) {'ok' if ok else 'NOT ON CHUNKS'}")
            if not ok:
                bad.append(f"the edge of the captured land round the player is not the chunks' hard edge in {shot}")
        if name == "fog-near":
            continue
        shown, n = land_share(pwx + 0.5, pwz + 0.5, 1)
        ok = shown >= 5
        print(f"{shot} ({name}): round the player {shown} of {n} pixels show land {'ok' if ok else 'HIDDEN'}")
        if not ok:
            bad.append(f"the land round the player is not visible in {shot}")
        shown, n = land_share(*FOG_PATCH, 1)
        ok = shown >= (5 if name == "fog-mid" else 1)
        print(f"{shot} ({name}): the 80-block patch: {shown} of {n} pixels show land {'ok' if ok else 'HIDDEN'}")
        if not ok:
            bad.append(f"the 80-block patch is not visible in {shot}")
        shown, n = land_share(*FOG_MID, 1)
        ok = shown == 0
        print(f"{shot} ({name}): half way between them {n - shown} of {n} pixels are the background {'ok' if ok else 'NOT BACKGROUND'}")
        if not ok:
            bad.append(f"the unexplored land between the player and the patch is not the background in {shot}")


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
    # and MM-5's ("done fog" is MM-9's picture check, which took the fog check's place)
    for end in ("SELFTEST client done bake", "SELFTEST client done far", "SELFTEST client done unload",
                "SELFTEST client done fog", "SELFTEST client done overlays", "SELFTEST client done dimension",
                "SELFTEST client done ownfog", "SELFTEST client done waypoints:", "SELFTEST client done waypoints rejoin",
                "SELFTEST client done waypoints nether"):
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
    # the seam check: the map's tiles meet exactly, with no line of the background colour between two of them
    try:
        from PIL import Image
    except ImportError:
        Image = None
        bad.append("Pillow is missing, so the tile-gap check could not run")
    # 02 level 2, 03 live, 04 close, 05 far (a large made-up area, level 3), 06 level 1, 07 level 0 baked, 08 filters
    for shot in ("client-02.png", "client-03.png", "client-04.png", "client-05.png", "client-06.png", "client-07.png", "client-08.png",
                 "local-02.png", "local-03.png", "local-04.png", "local-05.png", "local-10.png", "local-11.png", "local-12.png"):
        if Image is None or not os.path.exists(os.path.join(ci, shot)):
            continue
        im = Image.open(os.path.join(ci, shot)).convert("RGB")
        px, (w, h) = im.load(), im.size
        # a gap is a line, a pixel wide, of the background (unexplored land, and where no tile is drawn) between two pixels
        # of land (MM-9: the background is one flat colour, darker than any land the map shows)

        def between(a, b, c):
            # a blend of its neighbours (an averaged border between two colours), not the background showing through
            return all(min(b[i], c[i]) - 3 <= a[i] <= max(b[i], c[i]) + 3 for i in range(3))

        def gap(a, b, c):
            return fogged(a) and not fogged(b) and not fogged(c) and not between(a, b, c)
        gaps = [x for x in range(285, w - 1) if sum(1 for y in range(40, h - 20) if gap(px[x, y], px[x - 1, y], px[x + 1, y])) > 30]
        gaps += [y for y in range(40, h - 21) if sum(1 for x in range(285, w - 1) if gap(px[x, y], px[x, y - 1], px[x, y + 1])) > 30]
        print(f"{shot}: {len(gaps)} lines of background between tiles{' at ' + str(gaps) if gaps else ''}")
        if gaps:
            bad.append(f"lines between map tiles in {shot}")
    if Image is not None:
        patch(ci, Image, bad)
        fog(ci, Image, bad)
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
