#!/usr/bin/env python3
"""Builds app/src/main/assets/basemap/world.fxmap from Natural Earth shapefiles.

Natural Earth (https://www.naturalearthdata.com) is in the PUBLIC DOMAIN; no attribution is
required, and the app credits it anyway (About screen, docs/DEPENDENCIES.md).

Usage:
    python3 scripts/gen_basemap.py fetch  <download-dir>     # downloads the 1:50m zips (needs network)
    python3 scripts/gen_basemap.py build  <download-dir> [out-file]

Deterministic: the same input zips give the same bytes (no timestamps, fixed ordering). Only the
Python standard library is used; the shapefile and dbf readers below are the few lines of the
formats this needs (polygon, polyline, point; dbf character/numeric fields).

File format "FXB1" (the Kotlin reader is app/.../basemap/BasemapCodec.kt, keep them in step):
    magic        4 bytes  "FXB1"
    layerCount   varint
    per layer:   kind u8 (0 land ring, 1 lake ring, 2 border line)
                 pathCount varint
                 per path: pointCount varint, then points as zigzag varints of 1/100 degree:
                           the first point absolute (lon, lat), the rest deltas from the previous
    cityCount    varint
    per city:    lon, lat (zigzag varint, 1/100 degree, absolute), nameLen u8, name UTF-8
                 Cities are sorted by population, largest first, so a city's index IS its rank.
"""
import io
import struct
import sys
import urllib.request
import zipfile
from pathlib import Path

BASE = "https://naturalearth.s3.amazonaws.com/"
SOURCES = {
    "land": "50m_physical/ne_50m_land",
    "lakes": "50m_physical/ne_50m_lakes",
    "borders": "50m_cultural/ne_50m_admin_0_boundary_lines_land",
    "places": "50m_cultural/ne_50m_populated_places_simple",
}
MAX_CITIES = 1000
MIN_LAKE_POINTS = 6        # drops the specks; the big lakes stay


def fetch(dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    for path in SOURCES.values():
        name = path.split("/")[-1] + ".zip"
        with urllib.request.urlopen(BASE + path + ".zip", timeout=120) as r:
            (dest / name).write_bytes(r.read())
        print("fetched", name)


def read_member(zpath: Path, suffix: str) -> bytes:
    with zipfile.ZipFile(zpath) as z:
        for n in sorted(z.namelist()):
            if n.lower().endswith(suffix):
                return z.read(n)
    raise SystemExit(f"{zpath}: no {suffix}")


def shapes(data: bytes):
    """Yields (shape_type, parts, points) per record; points are (lon, lat)."""
    pos = 100
    while pos + 8 <= len(data):
        _num, length = struct.unpack(">ii", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length * 2]
        pos += 8 + length * 2
        stype = struct.unpack("<i", body[:4])[0]
        if stype == 0:
            continue
        if stype in (3, 5):
            nparts, npts = struct.unpack("<ii", body[36:44])
            parts = list(struct.unpack(f"<{nparts}i", body[44:44 + 4 * nparts]))
            off = 44 + 4 * nparts
            pts = [struct.unpack("<dd", body[off + 16 * i:off + 16 * i + 16]) for i in range(npts)]
            yield stype, parts, pts
        elif stype == 1:
            x, y = struct.unpack("<dd", body[4:20])
            yield stype, [0], [(x, y)]


def dbf(data: bytes):
    nrec, hlen, rlen = struct.unpack("<IHH", data[4:12])
    fields, pos = [], 32
    while data[pos] != 0x0D:
        name = data[pos:pos + 11].split(b"\0")[0].decode("ascii")
        flen = data[pos + 16]
        fields.append((name, flen))
        pos += 32
    rows = []
    for i in range(nrec):
        rec = data[hlen + i * rlen:hlen + (i + 1) * rlen]
        off, row = 1, {}
        for name, flen in fields:
            row[name] = rec[off:off + flen].decode("utf-8", "replace").strip()
            off += flen
        rows.append(row)
    return rows


def q(v: float) -> int:
    return int(round(v * 100.0))


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zz(n: int) -> bytes:
    return varint((n << 1) ^ (n >> 63))


def paths_of(parts, pts, min_points):
    ends = parts[1:] + [len(pts)]
    out = []
    for a, b in zip(parts, ends):
        quant = []
        for lon, lat in pts[a:b]:
            p = (q(lon), q(lat))
            if not quant or quant[-1] != p:
                quant.append(p)
        if len(quant) >= min_points:
            out.append(quant)
    return out


def encode_layer(kind: int, paths) -> bytes:
    out = bytearray([kind]) + varint(len(paths))
    for path in paths:
        out += varint(len(path))
        px = py = 0
        for i, (x, y) in enumerate(path):
            out += zz(x - px) + zz(y - py)  # first point is a delta from (0, 0), i.e. absolute
            px, py = x, y
    return bytes(out)


def build(src: Path, out_file: Path) -> None:
    def load(key):
        z = src / (SOURCES[key].split("/")[-1] + ".zip")
        return list(shapes(read_member(z, ".shp"))), dbf(read_member(z, ".dbf"))

    layers = []
    land, _ = load("land")
    layers.append(encode_layer(0, [p for _t, parts, pts in land for p in paths_of(parts, pts, 4)]))
    lakes, _ = load("lakes")
    layers.append(encode_layer(1, [p for _t, parts, pts in lakes for p in paths_of(parts, pts, MIN_LAKE_POINTS)]))
    borders, _ = load("borders")
    layers.append(encode_layer(2, [p for _t, parts, pts in borders for p in paths_of(parts, pts, 2)]))

    places, rows = load("places")
    cities = []
    for (_t, _parts, pts), row in zip(places, rows):
        name = row.get("name") or row.get("nameascii") or ""
        pop = int(float(row.get("pop_max") or 0))
        if not name:
            continue
        cities.append((-pop, name, q(pts[0][0]), q(pts[0][1])))
    cities.sort()  # population descending, then name: stable
    cities = cities[:MAX_CITIES]

    blob = bytearray(b"FXB1") + varint(len(layers))
    for layer in layers:
        blob += layer
    blob += varint(len(cities))
    for _pop, name, lon, lat in cities:
        raw = name.encode("utf-8")[:60]
        blob += zz(lon) + zz(lat) + bytes([len(raw)]) + raw
    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_bytes(bytes(blob))
    print(f"{out_file}: {len(blob)} bytes, {len(cities)} cities")


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in ("fetch", "build"):
        raise SystemExit(__doc__)
    d = Path(sys.argv[2])
    if sys.argv[1] == "fetch":
        fetch(d)
    else:
        out = Path(sys.argv[3]) if len(sys.argv) > 3 else Path(__file__).resolve().parent.parent / "app/src/main/assets/basemap/world.fxmap"
        build(d, out)
