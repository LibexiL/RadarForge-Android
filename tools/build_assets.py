#!/usr/bin/env python3
"""Builds app/assets/basemap.bin and app/assets/sites.json.

Inputs: a RadarForge (PC) maps.npz and sites.json - pass their paths, e.g.
    python3 tools/build_assets.py ../RadarForge/radarforge/assets/maps.npz ../RadarForge/radarforge/data/sites.json
Needs numpy. The outputs are committed, so this only runs when the map data changes.
"""
import json
import struct
import sys
from pathlib import Path

import numpy as np

OUT = Path(__file__).resolve().parent.parent / "app" / "assets"
LAYERS = ["countries", "states", "lakes", "counties", "roads", "roads2"]


def morton(a, b):
    """Interleave two 16-bit ints (spatial sort key)."""
    def spread(v):
        v = (v | (v << 8)) & 0x00FF00FF
        v = (v | (v << 4)) & 0x0F0F0F0F
        v = (v | (v << 2)) & 0x33333333
        v = (v | (v << 1)) & 0x55555555
        return v
    return spread(a) | (spread(b) << 1)


def simplify(seg, tol):
    """Ramer-Douglas-Peucker on one line (degrees)."""
    if tol <= 0 or len(seg) < 3:
        return seg
    keep = np.zeros(len(seg), bool)
    keep[0] = keep[-1] = True
    stack = [(0, len(seg) - 1)]
    while stack:
        a, b = stack.pop()
        if b <= a + 1:
            continue
        p, q = seg[a], seg[b]
        d = q - p
        n = np.hypot(d[0], d[1])
        pts = seg[a + 1:b]
        if n < 1e-12:
            dist = np.hypot(pts[:, 0] - p[0], pts[:, 1] - p[1])
        else:
            dist = np.abs(d[0] * (p[1] - pts[:, 1]) - d[1] * (p[0] - pts[:, 0])) / n
        i = int(np.argmax(dist))
        if dist[i] > tol:
            m = a + 1 + i
            keep[m] = True
            stack.append((a, m))
            stack.append((m, b))
    return seg[keep]


TOLERANCE = {"counties": 0.002, "countries": 0.002, "lakes": 0.002, "states": 0.001, "roads": 0.001, "roads2": 0.002}


def main(npz_path, sites_path):
    d = np.load(npz_path, allow_pickle=True)
    out = bytearray(b"RFMAP1")
    out += struct.pack("<H", len(LAYERS))
    for name in LAYERS:
        pts = d[f"{name}_pts"].astype(np.float32)            # (lon, lat)
        starts = d[f"{name}_starts"].astype(np.int64)
        extra = d["counties_fips"].astype(np.int32) if name == "counties" else None
        ends = np.append(starts[1:], len(pts))
        lines = []
        for i, (s, e) in enumerate(zip(starts, ends)):
            if e - s < 2:
                continue
            seg = simplify(pts[s:e], TOLERANCE.get(name, 0))
            mid = seg.mean(axis=0)
            key = morton(int((mid[1] + 90) / 1.0) & 0xFFFF, int((mid[0] + 180) / 1.0) & 0xFFFF)
            lines.append((key, seg, int(extra[i]) if extra is not None and i < len(extra) else 0))
        lines.sort(key=lambda t: t[0])
        all_pts = np.concatenate([l[1] for l in lines]).astype("<f4")
        new_starts = np.cumsum([0] + [len(l[1]) for l in lines[:-1]]).astype("<i4")
        ex = np.array([l[2] for l in lines], dtype="<i4")
        nb = name.encode()
        out += struct.pack("<B", len(nb)) + nb
        out += struct.pack("<II", len(lines), len(all_pts))
        out += new_starts.tobytes() + ex.tobytes() + all_pts.tobytes()
        print(f"{name:10s} {len(lines):6d} lines {len(all_pts):8d} points")
    lat = d["city_lat"].astype("<f4")
    lon = d["city_lon"].astype("<f4")
    pop = d["city_pop"].astype("<i4")
    names = [" ".join(str(n).split()) for n in d["city_name"]]
    # drop duplicate spellings of the same place (e.g. "New York" / "New York City")
    keep = []
    seen = []
    order = np.argsort(-pop)
    for i in order:
        dup = False
        for j in seen[-400:]:
            if abs(lat[i] - lat[j]) < 0.08 and abs(lon[i] - lon[j]) < 0.08 and (names[i] in names[j] or names[j] in names[i]):
                dup = True
                break
        if not dup:
            keep.append(i)
            seen.append(i)
    keep = np.array(sorted(keep))
    out += struct.pack("<I", len(keep))
    out += lat[keep].tobytes() + lon[keep].tobytes() + pop[keep].tobytes()
    for i in keep:
        b = names[i].encode("utf-8")[:255]
        out += struct.pack("<B", len(b)) + b
    print(f"cities     {len(keep):6d}")
    (OUT / "basemap.bin").write_bytes(out)
    print(f"wrote {OUT / 'basemap.bin'} ({len(out) / 1e6:.1f} MB)")

    sites = [s for s in json.load(open(sites_path)) if s.get("type") == "wsr88d"]
    (OUT / "sites.json").write_text(json.dumps(sites, separators=(",", ":")))
    print(f"wrote {len(sites)} radar sites")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
