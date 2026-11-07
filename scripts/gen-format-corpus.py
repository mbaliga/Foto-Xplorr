#!/usr/bin/env python3
"""Generates the synthetic format corpus in testdata/formats/ (WP1.8).

Everything here is built by hand from the formats' own specs, from a fixed 4-quadrant test
picture (red / green / blue / yellow), with the Python standard library only. No real photo and no
third-party sample file is used or copied, so the corpus is ours (CC0) and re-creatable
byte-for-byte: run `python3 scripts/gen-format-corpus.py` and `git diff` stays empty.

Two kinds of file:
  * real, tiny, VALID files (16x16 unless noted) -- the platform decoders are expected to open
    the ones the manifest marks `platform`; the rest are valid files of formats the app only
    sniffs today (nothing in the app decodes them yet).
  * `stub-*` files: the first bytes of a format that needs a real encoder (HEIC, AVIF, JXL, EXR,
    JP2, RAW families...). They exist ONLY so FormatSniffer has a magic number to recognise.
    They are not images and are never claimed to be decodable.

The one exception to "standard library only" is the lossy WebP (`webp-lossy.webp`)
needs a VP8 encoder, so it is encoded once with `ffmpeg` (libwebp) from our own quadrant picture
and committed; pass --lossy-with-ffmpeg to re-create it. Without that flag the committed file is
left alone (and the run fails if it is missing).

Also writes manifest.txt and SOURCES.md from the same table, so they cannot drift from the files.
"""
import os
import struct
import subprocess
import sys
import zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "testdata", "formats")
W = H = 16
RED, GREEN, BLUE, YELLOW = (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)


def quad(x, y, w=W, h=H, colours=(RED, GREEN, BLUE, YELLOW)):
    """Top-left, top-right, bottom-left, bottom-right."""
    return colours[(1 if x >= w // 2 else 0) + (2 if y >= h // 2 else 0)]


def rows(w=W, h=H, colours=(RED, GREEN, BLUE, YELLOW)):
    return [[quad(x, y, w, h, colours) for x in range(w)] for y in range(h)]


def rgb_bytes(w=W, h=H, colours=(RED, GREEN, BLUE, YELLOW)):
    return b"".join(bytes(p) for r in rows(w, h, colours) for p in r)


FILES = []  # (name, data, sniff, decode, w, h, check, description)
CHECK_QUAD = "quad"


def add(name, data, sniff, decode, w, h, check, description):
    FILES.append((name, data, sniff, decode, w, h, check, description))


# ---------------------------------------------------------------- PNG / APNG
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


PNG_SIG = b"\x89PNG\r\n\x1a\n"


def png(w, h, ctype, depth, raw_rows, plte=None, extra_before_idat=b"", idat_chunks=None):
    ihdr = struct.pack(">IIBBBBB", w, h, depth, ctype, 0, 0, 0)
    body = b"".join(b"\x00" + r for r in raw_rows)
    out = PNG_SIG + chunk(b"IHDR", ihdr)
    if plte:
        out += chunk(b"PLTE", plte)
    out += extra_before_idat
    out += idat_chunks if idat_chunks is not None else chunk(b"IDAT", zlib.compress(body, 9))
    return out + chunk(b"IEND", b"")


def png_rgb(colours=(RED, GREEN, BLUE, YELLOW), extra=b""):
    rr = [b"".join(bytes(p) for p in r) for r in rows(colours=colours)]
    return png(W, H, 2, 8, rr, extra_before_idat=extra)


def apng():
    f1 = png_rgb()
    f2 = png_rgb(colours=(YELLOW, BLUE, GREEN, RED))
    idat1 = [c for c in split_chunks(f1) if c[0] == b"IDAT"][0][1]
    idat2 = [c for c in split_chunks(f2) if c[0] == b"IDAT"][0][1]
    ihdr = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
    fctl = lambda seq: chunk(b"fcTL", struct.pack(">IIIIIHHBB", seq, W, H, 0, 0, 10, 100, 0, 0))
    out = PNG_SIG + chunk(b"IHDR", ihdr) + chunk(b"acTL", struct.pack(">II", 2, 0))
    out += fctl(0) + chunk(b"IDAT", idat1)
    out += fctl(1) + chunk(b"fdAT", struct.pack(">I", 2) + idat2)
    return out + chunk(b"IEND", b"")


def split_chunks(png_bytes):
    i, out = 8, []
    while i < len(png_bytes):
        n = struct.unpack(">I", png_bytes[i:i + 4])[0]
        out.append((png_bytes[i + 4:i + 8], png_bytes[i + 8:i + 8 + n]))
        i += 12 + n
    return out


def gen_png():
    add("png-rgb8.png", png_rgb(), "png", "platform", W, H, CHECK_QUAD, "8-bit RGB, one IDAT; the baseline PNG")
    rgba = [b"".join(bytes(p) + b"\xff" for p in r) for r in rows()]
    add("png-rgba8.png", png(W, H, 6, 8, rgba), "png", "platform", W, H, CHECK_QUAD, "8-bit RGBA, opaque; alpha-channel path")
    gray = [bytes((x * 16) & 0xFF for x in range(W)) for _ in range(H)]
    add("png-gray8.png", png(W, H, 0, 8, gray), "png", "platform", W, H, "-", "8-bit greyscale horizontal ramp")
    idx = [bytes((1 if x >= W // 2 else 0) + (2 if y >= H // 2 else 0) for x in range(W)) for y in range(H)]
    plte = bytes(RED + GREEN + BLUE + YELLOW)
    add("png-palette8.png", png(W, H, 3, 8, idx, plte=plte), "png", "platform", W, H, CHECK_QUAD, "8-bit palette (colour type 3)")
    rgb16 = [b"".join(struct.pack(">HHH", *(c * 257 for c in p)) for p in r) for r in rows()]
    add("png-rgb16.png", png(W, H, 2, 16, rgb16), "png", "platform", W, H, CHECK_QUAD, "16-bit RGB; deep-colour PNG")
    add("apng-2frames.png", apng(), "apng", "platform", W, H, CHECK_QUAD, "APNG, 2 frames (FormatSniffer walks the chunks to the acTL before IDAT); first frame is the quadrant picture")


# ---------------------------------------------------------------- GIF
def lzw_gif(indices, min_code):
    clear, eoi = 1 << min_code, (1 << min_code) + 1
    out, acc, nbits = bytearray(), 0, 0
    width = min_code + 1
    table = {(i,): i for i in range(clear)}
    nxt = eoi + 1

    def emit(code):
        nonlocal acc, nbits
        acc |= code << nbits
        nbits += width
        while nbits >= 8:
            out.append(acc & 0xFF)
            acc >>= 8
            nbits -= 8

    emit(clear)
    cur = ()
    for v in indices:
        nxt_seq = cur + (v,)
        if nxt_seq in table:
            cur = nxt_seq
            continue
        emit(table[cur])
        table[nxt_seq] = nxt
        nxt += 1
        if nxt - 1 == (1 << width) and width < 12:
            width += 1
        if nxt >= 4095:
            emit(clear)
            table = {(i,): i for i in range(clear)}
            nxt = eoi + 1
            width = min_code + 1
        cur = (v,)
    if cur:
        emit(table[cur])
    emit(eoi)
    if nbits:
        out.append(acc & 0xFF)
    return bytes(out)


def sub_blocks(data):
    out = b""
    for i in range(0, len(data), 255):
        part = data[i:i + 255]
        out += bytes([len(part)]) + part
    return out + b"\x00"


def gif(frames, animated):
    pal = bytes(RED + GREEN + BLUE + YELLOW)
    out = b"GIF89a" + struct.pack("<HHBBB", W, H, 0xF1, 0, 0) + pal
    if animated:
        out += b"\x21\xff\x0bNETSCAPE2.0\x03\x01\x00\x00\x00"
    for order in frames:
        if animated:
            out += b"\x21\xf9\x04\x00" + struct.pack("<H", 10) + b"\x00\x00"
        out += b"\x2c" + struct.pack("<HHHHB", 0, 0, W, H, 0) + b"\x02"
        idx = [order[(1 if x >= W // 2 else 0) + (2 if y >= H // 2 else 0)] for y in range(H) for x in range(W)]
        out += sub_blocks(lzw_gif(idx, 2))
    return out + b"\x3b"


def gen_gif():
    add("gif-static.gif", gif([(0, 1, 2, 3)], False), "gif", "platform", W, H, CHECK_QUAD, "GIF89a, one frame, 4-colour global palette")
    add("gif-animated.gif", gif([(0, 1, 2, 3), (3, 2, 1, 0)], True), "gif", "platform", W, H, CHECK_QUAD, "GIF89a, 2 frames, NETSCAPE loop; first frame is the quadrant picture")


# ---------------------------------------------------------------- WebP
class Bits:
    def __init__(self):
        self.acc = 0
        self.n = 0

    def put(self, value, nbits):
        self.acc |= value << self.n
        self.n += nbits

    def bytes(self):
        return self.acc.to_bytes((self.n + 7) // 8, "little")


def vp8l(pixels, w, h):
    """Lossless bitstream with 1-or-2-symbol simple prefix codes per channel (no transforms, no
    cache): every pixel costs at most 3 bits, and the picture may use up to 2 distinct values in
    each of G, R, B (A and distance need 1)."""
    chans = [[(p[1]) for p in pixels], [p[0] for p in pixels], [p[2] for p in pixels], [255] * len(pixels)]
    b = Bits()
    b.put(w - 1, 14)
    b.put(h - 1, 14)
    b.put(0, 1)  # alpha_is_used
    b.put(0, 3)  # version
    b.put(0, 1)  # no transform
    b.put(0, 1)  # no colour cache
    b.put(0, 1)  # no meta prefix image
    syms = []
    for values in chans + [[0]]:
        s = sorted(set(values))
        assert len(s) <= 2
        syms.append(s)
        b.put(1, 1)  # simple code
        b.put(len(s) - 1, 1)
        b.put(1, 1)  # first symbol uses 8 bits
        b.put(s[0], 8)
        if len(s) == 2:
            b.put(s[1], 8)
    for i in range(len(pixels)):
        for c in range(3):  # green, red, blue; alpha and distance are single-symbol (0 bits)
            s = syms[c]
            if len(s) == 2:
                b.put(s.index(chans[c][i]), 1)
    return b.bytes()


def riff(chunks):
    body = b"WEBP" + b"".join(chunks)
    return b"RIFF" + struct.pack("<I", len(body)) + body


def wchunk(kind, data):
    return kind + struct.pack("<I", len(data)) + data + (b"\x00" if len(data) % 2 else b"")


def u24(v):
    return struct.pack("<I", v)[:3]


def gen_webp(lossy_ffmpeg):
    px = [quad(x, y) for y in range(H) for x in range(W)]
    add("webp-lossless.webp", riff([wchunk(b"VP8L", b"\x2f" + vp8l(px, W, H))]), "webp", "platform", W, H, CHECK_QUAD, "Lossless WebP (VP8L) built by hand with 1-bit prefix codes")
    frames = []
    for col in (RED, BLUE):
        data = b"\x2f" + vp8l([col] * (W * H), W, H)
        frames.append(wchunk(b"ANMF", u24(0) + u24(0) + u24(W - 1) + u24(H - 1) + u24(100) + b"\x00" + wchunk(b"VP8L", data)))
    vp8x = wchunk(b"VP8X", struct.pack("<B", 0x02) + b"\x00\x00\x00" + u24(W - 1) + u24(H - 1))
    anim = wchunk(b"ANIM", struct.pack("<IH", 0, 0))
    add("webp-animated.webp", riff([vp8x, anim] + frames), "webp", "platform", W, H, "-", "Animated WebP (VP8X + ANIM + 2 ANMF lossless frames, solid red then solid blue)")
    path = os.path.join(OUT, "webp-lossy.webp")
    if lossy_ffmpeg:
        ppm = b"P6\n16 16\n255\n" + rgb_bytes()
        res = subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "image2pipe", "-c:v", "ppm", "-i", "-", "-frames:v", "1",
             "-c:v", "libwebp", "-lossless", "0", "-q:v", "80", "-f", "webp", "-"],
            input=ppm, stdout=subprocess.PIPE, check=True)
        with open(path, "wb") as f:
            f.write(res.stdout)
    if not os.path.exists(path):
        sys.exit("webp-lossy.webp is missing: re-run once with --lossy-with-ffmpeg")
    add("webp-lossy.webp", open(path, "rb").read(), "webp", "platform", W, H, CHECK_QUAD, "Lossy WebP (VP8), encoded once by libwebp via ffmpeg from the quadrant picture (the one non-script-built file)")


# ---------------------------------------------------------------- JPEG (hand-built baseline, DC-only)
def huff_codes(counts, symbols):
    codes, code, k = {}, 0, 0
    for length in range(1, 17):
        for _ in range(counts[length - 1]):
            codes[symbols[k]] = (code, length)
            code += 1
            k += 1
        code <<= 1
    return codes


DC_COUNTS = [0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0]
DC_SYMS = list(range(12))
AC_COUNTS = [1] + [0] * 15
AC_SYMS = [0x00]  # only EOB: every block is a flat colour


def jpeg(w, h, gray, orientation=None):
    dc = huff_codes(DC_COUNTS, DC_SYMS)
    ac = huff_codes(AC_COUNTS, AC_SYMS)
    q = 8  # DC coefficient = (level - 128) * 8 / q = level - 128
    out = b"\xff\xd8"
    if orientation is not None:
        tiff = b"II*\x00" + struct.pack("<I", 8) + struct.pack("<H", 1) + struct.pack("<HHIHH", 0x0112, 3, 1, orientation, 0) + struct.pack("<I", 0)
        payload = b"Exif\x00\x00" + tiff
        out += b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload
    else:
        out += b"\xff\xe0" + struct.pack(">H", 16) + b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    out += b"\xff\xdb" + struct.pack(">HB", 67, 0) + bytes([q] * 64)
    ncomp = 1 if gray else 3
    sof = struct.pack(">BHHB", 8, h, w, ncomp) + b"".join(bytes([i + 1, 0x11, 0]) for i in range(ncomp))
    out += b"\xff\xc0" + struct.pack(">H", len(sof) + 2) + sof
    for cls, counts, syms in ((0x00, DC_COUNTS, DC_SYMS), (0x10, AC_COUNTS, AC_SYMS)):
        body = bytes([cls]) + bytes(counts) + bytes(syms)
        out += b"\xff\xc4" + struct.pack(">H", len(body) + 2) + body
    sos = bytes([ncomp]) + b"".join(bytes([i + 1, 0x00]) for i in range(ncomp)) + b"\x00\x3f\x00"
    out += b"\xff\xda" + struct.pack(">H", len(sos) + 2) + sos

    bits = Bits()
    prev = [0] * ncomp
    for by in range(h // 8):
        for bx in range(w // 8):
            r, g, b = quad(bx * 8, by * 8, w, h)
            if gray:
                levels = [round(0.299 * r + 0.587 * g + 0.114 * b)]
            else:
                levels = [
                    round(0.299 * r + 0.587 * g + 0.114 * b),
                    min(255, max(0, round(-0.168736 * r - 0.331264 * g + 0.5 * b + 128))),
                    min(255, max(0, round(0.5 * r - 0.418688 * g - 0.081312 * b + 128))),
                ]
            for c, level in enumerate(levels):
                coef = level - 128
                diff, prev[c] = coef - prev[c], coef
                cat = abs(diff).bit_length()
                code, length = dc[cat]
                put_msb(bits, code, length)
                if cat:
                    put_msb(bits, diff if diff > 0 else diff + (1 << cat) - 1, cat)
                put_msb(bits, *ac[0x00])
    data = msb_bytes(bits)
    out += data.replace(b"\xff", b"\xff\x00") + b"\xff\xd9"
    return out


def put_msb(bits, value, nbits):
    if not hasattr(bits, "list"):
        bits.list = []
    bits.list.extend(((value >> (nbits - 1 - i)) & 1) for i in range(nbits))


def msb_bytes(bits):
    lst = getattr(bits, "list", [])
    lst = lst + [1] * ((-len(lst)) % 8)
    return bytes(int("".join(map(str, lst[i:i + 8])), 2) for i in range(0, len(lst), 8))


def gen_jpeg():
    add("jpeg-baseline.jpg", jpeg(16, 16, False), "jpeg", "platform", 16, 16, CHECK_QUAD, "Baseline 4:4:4 JPEG with JFIF, flat 8x8 blocks, built by hand")
    add("jpeg-gray.jpg", jpeg(16, 16, True), "jpeg", "platform", 16, 16, "-", "Single-component (greyscale) JPEG")
    add("jpeg-exif-rot6.jpg", jpeg(16, 8, False, orientation=6), "jpeg", "platform", 8, 16, "-", "Stored 16x8 with EXIF Orientation=6 (rotate 90 CW): decodeUpright must report 8x16")


# ---------------------------------------------------------------- BMP / ICO
def bmp(bpp, topdown=False, palette=False):
    if palette:
        pal = b"".join(bytes([c[2], c[1], c[0], 0]) for c in (RED, GREEN, BLUE, YELLOW)) + bytes(4 * 252)
        pix = [bytes((1 if x >= W // 2 else 0) + (2 if y >= H // 2 else 0) for x in range(W)) for y in range(H)]
    else:
        pal = b""
        pix = [b"".join((bytes([p[2], p[1], p[0]]) + (b"\xff" if bpp == 32 else b"")) for p in r) for r in rows()]
    if not topdown:
        pix = pix[::-1]
    data = b"".join(pix)
    off = 14 + 40 + len(pal)
    hdr = struct.pack("<IiiHHIIiiII", 40, W, -H if topdown else H, 1, bpp, 0, len(data), 2835, 2835, 256 if palette else 0, 0)
    return b"BM" + struct.pack("<IHHI", off + len(data), 0, 0, off) + hdr + pal + data


def ico_dib():
    pix = [b"".join(bytes([p[2], p[1], p[0], 255]) for p in r) for r in rows()][::-1]
    mask = (b"\x00\x00\x00\x00") * H  # 1bpp AND mask, 16 px = 2 bytes padded to 4
    return struct.pack("<IiiHHIIiiII", 40, W, H * 2, 1, 32, 0, 0, 0, 0, 0, 0) + b"".join(pix) + mask


def ico(image, bpp):
    return struct.pack("<HHH", 0, 1, 1) + struct.pack("<BBBBHHII", W, H, 0, 0, 1, bpp, len(image), 22) + image


def gen_bmp_ico():
    add("bmp-24.bmp", bmp(24), "bmp", "platform", W, H, CHECK_QUAD, "24-bit BI_RGB, bottom-up")
    add("bmp-24-topdown.bmp", bmp(24, topdown=True), "bmp", "platform", W, H, CHECK_QUAD, "24-bit with negative height (top-down)")
    add("bmp-32.bmp", bmp(32), "bmp", "platform", W, H, CHECK_QUAD, "32-bit BI_RGB")
    add("bmp-8-palette.bmp", bmp(8, palette=True), "bmp", "platform", W, H, CHECK_QUAD, "8-bit with a 256-entry colour table")
    add("ico-png.ico", ico(png_rgb(), 32), "ico", "platform", W, H, CHECK_QUAD, "ICO whose single image is an embedded PNG")
    add("ico-dib.ico", ico(ico_dib(), 32), "ico", "platform", W, H, CHECK_QUAD, "ICO whose single image is a 32-bit DIB with AND mask")


# ---------------------------------------------------------------- TGA / QOI / PNM
def tga(rle=False, bpp=24, topleft=False, footer=False):
    desc = (0x20 if topleft else 0) | (8 if bpp == 32 else 0)
    hdr = struct.pack("<BBBHHBHHHHBB", 0, 0, 10 if rle else 2, 0, 0, 0, 0, 0, W, H, bpp, desc)
    rr = rows() if topleft else rows()[::-1]

    def px(p):
        return bytes([p[2], p[1], p[0]]) + (b"\xff" if bpp == 32 else b"")

    body = b""
    if rle:
        for r in rr:  # each 8-pixel half is one run packet
            for half in (r[:W // 2], r[W // 2:]):
                body += bytes([0x80 | (len(half) - 1)]) + px(half[0])
    else:
        body = b"".join(px(p) for r in rr for p in r)
    ext = b"\x00" * 8 + b"TRUEVISION-XFILE.\x00" if footer else b""
    return hdr + body + ext


def qoi():
    out = b"qoif" + struct.pack(">IIBB", W, H, 3, 0)
    prev, run = (0, 0, 0), 0
    for y in range(H):
        for x in range(W):
            p = quad(x, y)
            if p == prev:
                run += 1
                if run == 62:
                    out += bytes([0xC0 | (run - 1)])
                    run = 0
                continue
            if run:
                out += bytes([0xC0 | (run - 1)])
                run = 0
            out += b"\xfe" + bytes(p)
            prev = p
    if run:
        out += bytes([0xC0 | (run - 1)])
    return out + b"\x00" * 7 + b"\x01"


def pnm_parts():
    px = rows()
    gray = [[(x * 16) & 0xFF for x in range(W)] for _ in range(H)]
    bit = [[1 if (x // 4 + y // 4) % 2 == 0 else 0 for x in range(W)] for y in range(H)]
    p1 = "P1\n# generated\n16 16\n" + "\n".join(" ".join(map(str, r)) for r in bit) + "\n"
    p2 = "P2\n16 16\n255\n" + "\n".join(" ".join(map(str, r)) for r in gray) + "\n"
    p3 = "P3\n16 16\n255\n" + "\n".join(" ".join(f"{c}" for p in r for c in p) for r in px) + "\n"
    p4 = b"P4\n16 16\n" + b"".join(bytes([int("".join(map(str, r[:8])), 2), int("".join(map(str, r[8:])), 2)]) for r in bit)
    p5 = b"P5\n16 16\n255\n" + bytes(v for r in gray for v in r)
    p6 = b"P6\n16 16\n255\n" + rgb_bytes()
    return p1.encode(), p2.encode(), p3.encode(), p4, p5, p6


def gen_simple():
    add("tga-24.tga", tga(), "none", "none", W, H, CHECK_QUAD, "Uncompressed 24-bit truecolour, bottom-left origin. FormatSniffer cannot identify TGA (no header magic): expected `none`")
    add("tga-rle-32.tga", tga(True, 32, True, True), "none", "none", W, H, CHECK_QUAD, "RLE 32-bit, top-left origin, with the TRUEVISION-XFILE footer")
    add("qoi-rgb.qoi", qoi(), "qoi", "none", W, H, CHECK_QUAD, "QOI, RGB, RGB and RUN ops only")
    names = ["pnm-p1.pbm", "pnm-p2.pgm", "pnm-p3.ppm", "pnm-p4.pbm", "pnm-p5.pgm", "pnm-p6.ppm"]
    descs = ["P1 ASCII bitmap", "P2 ASCII greymap", "P3 ASCII pixmap", "P4 binary bitmap", "P5 binary greymap", "P6 binary pixmap"]
    for n, d, data in zip(names, descs, pnm_parts()):
        add(n, data, "pnm", "none", W, H, CHECK_QUAD if n.endswith("p3.ppm") or n.endswith("p6.ppm") else "-", "Netpbm " + d)


# ---------------------------------------------------------------- TIFF
def packbits(data):
    out, i, lit = b"", 0, b""

    def flush():
        nonlocal out, lit
        while lit:
            out += bytes([len(lit[:128]) - 1]) + lit[:128]
            lit = lit[128:]

    while i < len(data):
        j = i
        while j + 1 < len(data) and data[j + 1] == data[i] and j - i < 127:
            j += 1
        n = j - i + 1
        if n >= 2:
            flush()
            out += bytes([257 - n, data[i]])
        else:
            lit += bytes([data[i]])
        i = j + 1
    flush()
    return out


def lzw_tiff(data):
    out, acc, nbits = bytearray(), 0, 0
    width = 9
    table = {bytes([i]): i for i in range(256)}
    nxt = 258

    def emit(code):
        nonlocal acc, nbits
        acc = (acc << width) | code
        nbits += width
        while nbits >= 8:
            out.append((acc >> (nbits - 8)) & 0xFF)
            nbits -= 8
        acc &= (1 << nbits) - 1

    emit(256)
    cur = b""
    for v in data:
        seq = cur + bytes([v])
        if seq in table:
            cur = seq
            continue
        emit(table[cur])
        table[seq] = nxt
        nxt += 1
        if nxt == (1 << width) - 1 and width < 12:  # early change
            width += 1
        if nxt >= 4094:
            emit(256)
            table = {bytes([i]): i for i in range(256)}
            nxt, width = 258, 9
        cur = bytes([v])
    if cur:
        emit(table[cur])
        # the decoder adds one entry after this code too, which can bump the width before EOI
        nxt += 1
        if nxt == (1 << width) - 1 and width < 12:
            width += 1
    emit(257)
    if nbits:
        out.append((acc << (8 - nbits)) & 0xFF)
    return bytes(out)


def tiff(compression, big_endian=False):
    e = ">" if big_endian else "<"
    raw = rgb_bytes()
    if compression == 32773:
        strip = b"".join(packbits(raw[i:i + W * 3]) for i in range(0, len(raw), W * 3))
    else:
        strip = {1: raw, 8: zlib.compress(raw, 9), 5: lzw_tiff(raw)}[compression]
    # layout: header(8) | strip | pad | bits-per-sample(6) | IFD
    head = (b"MM\x00\x2a" if big_endian else b"II*\x00") + struct.pack(e + "I", 0)
    off_strip = 8
    pad = len(strip) % 2
    off_bps = off_strip + len(strip) + pad
    off_ifd = off_bps + 6
    entries = [
        (256, 3, 1, W), (257, 3, 1, H), (258, 3, 3, off_bps), (259, 3, 1, compression), (262, 3, 1, 2),
        (273, 4, 1, off_strip), (277, 3, 1, 3), (278, 3, 1, H), (279, 4, 1, len(strip)), (284, 3, 1, 1),
    ]

    def entry(t, ty, n, v):
        if ty == 3 and n == 1:
            return struct.pack(e + "HHIHH", t, ty, n, v, 0)
        return struct.pack(e + "HHII", t, ty, n, v)

    ifd = struct.pack(e + "H", len(entries)) + b"".join(entry(*x) for x in entries) + struct.pack(e + "I", 0)
    head = head[:4] + struct.pack(e + "I", off_ifd)
    return head + strip + b"\x00" * pad + struct.pack(e + "HHH", 8, 8, 8) + ifd


def gen_tiff():
    add("tiff-none-le.tif", tiff(1), "tiff", "none", W, H, CHECK_QUAD, "TIFF little-endian, RGB, uncompressed, one strip")
    add("tiff-none-be.tif", tiff(1, True), "tiff", "none", W, H, CHECK_QUAD, "TIFF big-endian (MM), RGB, uncompressed")
    add("tiff-packbits.tif", tiff(32773), "tiff", "none", W, H, CHECK_QUAD, "TIFF compression 32773 (PackBits)")
    add("tiff-lzw.tif", tiff(5), "tiff", "none", W, H, CHECK_QUAD, "TIFF compression 5 (LZW, MSB-first, early change)")
    add("tiff-deflate.tif", tiff(8), "tiff", "none", W, H, CHECK_QUAD, "TIFF compression 8 (Adobe Deflate)")


# ---------------------------------------------------------------- PSD / HDR / ICNS / SVG
def psd(rle):
    planes = [bytes(quad(x, y)[c] for y in range(H) for x in range(W)) for c in range(3)]
    out = b"8BPS" + struct.pack(">H", 1) + b"\x00" * 6 + struct.pack(">HIIHH", 3, H, W, 8, 3)
    out += struct.pack(">I", 0) + struct.pack(">I", 0) + struct.pack(">I", 0)  # colour mode, resources, layers
    if not rle:
        return out + struct.pack(">H", 0) + b"".join(planes)
    lines = [packbits(p[r * W:(r + 1) * W]) for p in planes for r in range(H)]
    return out + struct.pack(">H", 1) + b"".join(struct.pack(">H", len(l)) for l in lines) + b"".join(lines)


def rgbe(p):
    return bytes([128 if p[0] else 0, 128 if p[1] else 0, 128 if p[2] else 0, 129])


def hdr():
    return b"#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 16 +X 16\n" + b"".join(rgbe(p) for r in rows() for p in r)


def icns():
    inner = png_rgb()
    el = b"icp4" + struct.pack(">I", len(inner) + 8) + inner
    return b"icns" + struct.pack(">I", len(el) + 8) + el


def svg():
    return (b'<?xml version="1.0" encoding="UTF-8"?>\n'
            b'<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">\n'
            b'<rect x="0" y="0" width="8" height="8" fill="#f00"/><rect x="8" y="0" width="8" height="8" fill="#0f0"/>\n'
            b'<rect x="0" y="8" width="8" height="8" fill="#00f"/><rect x="8" y="8" width="8" height="8" fill="#ff0"/>\n</svg>\n')


def zip_stored(entries):
    """A ZIP of stored (uncompressed) entries in the given order, with a fixed timestamp."""
    import io
    import zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            z.writestr(info, data)
    return buf.getvalue()


def gen_misc():
    stack = b'<?xml version="1.0"?><image w="16" h="16"><stack><layer src="mergedimage.png" x="0" y="0"/></stack></image>'
    add("ora-valid.ora", zip_stored([("mimetype", b"image/openraster"), ("stack.xml", stack), ("mergedimage.png", png_rgb())]), "ora", "none", W, H, "-", "OpenRaster: ZIP, `mimetype` stored first, stack.xml, mergedimage.png (a valid file, not a stub; the app cannot open it yet)")
    add("kra-valid.kra", zip_stored([("mimetype", b"application/x-krita"), ("maindoc.xml", b"<DOC/>"), ("mergedimage.png", png_rgb())]), "kra", "none", W, H, "-", "Krita-shaped ZIP: `mimetype` application/x-krita first plus mergedimage.png (minimal, not a real Krita document)")
    add("zip-plain.zip", zip_stored([("hello.txt", b"hello\n")]), "none", "none", 0, 0, "-", "Negative control: an ordinary ZIP must not be claimed as ORA or KRA")
    add("psd-raw.psd", psd(False), "psd", "none", W, H, CHECK_QUAD, "PSD v1, RGB 8-bit, composite image only (raw planar); no layers")
    add("psd-rle.psd", psd(True), "psd", "none", W, H, CHECK_QUAD, "PSD, composite only, PackBits per scanline")
    add("hdr-rgbe.hdr", hdr(), "hdr", "none", W, H, CHECK_QUAD, "Radiance RGBE, flat (non-RLE) scanlines")
    add("icns-png.icns", icns(), "icns", "none", W, H, CHECK_QUAD, "ICNS with one `icp4` (16x16 PNG) element")
    add("svg-quad.svg", svg(), "none", "none", W, H, CHECK_QUAD, "SVG text. FormatSniffer matches binary magic only: expected `none`; MediaFormat.classify handles SVG by mime/extension")


# ---------------------------------------------------------------- header-only stubs
def stub(data):
    return data + b"\x00" * (32 - len(data)) if len(data) < 32 else data


def ftyp(brand):
    return struct.pack(">I", 24) + b"ftyp" + brand + b"\x00\x00\x00\x00" + brand + b"mif1"


def tiff_stub(extra=b""):
    return stub(b"II*\x00\x08\x00\x00\x00" + extra)


def gen_stubs():
    S = [
        ("stub-heic.heic", ftyp(b"heic"), "heif", "HEIC: ISOBMFF `ftyp` + brand heic"),
        ("stub-avif.avif", ftyp(b"avif"), "avif", "AVIF: ISOBMFF `ftyp` + brand avif"),
        ("stub-jxl-codestream.jxl", stub(b"\xff\x0a"), "jxl", "JPEG XL bare codestream signature FF 0A"),
        ("stub-jxl-container.jxl", stub(bytes.fromhex("0000000c4a584c200d0a870a")), "jxl", "JPEG XL ISOBMFF container signature"),
        ("stub-exr.exr", stub(bytes.fromhex("762f3101") + b"\x02\x00\x00\x00"), "exr", "OpenEXR magic 76 2F 31 01"),
        ("stub-jp2.jp2", stub(bytes.fromhex("0000000c6a5020200d0a870a")), "jp2", "JPEG 2000 JP2 signature box"),
        ("stub-j2k.j2k", stub(b"\xff\x4f\xff\x51"), "jp2", "JPEG 2000 raw codestream (FF 4F)"),
        ("stub-jxr.jxr", stub(b"II\xbc\x01"), "jxr", "JPEG XR: II BC"),
        ("stub-dds.dds", stub(b"DDS \x7c\x00\x00\x00"), "dds", "DirectDraw Surface magic"),
        ("stub-ktx2.ktx2", stub(bytes.fromhex("ab4b5458203230bb0d0a1a0a")), "ktx", "KTX 2.0 identifier"),
        ("stub-pcx.pcx", stub(b"\x0a\x05\x01\x08"), "pcx", "PCX: manufacturer 0x0A, RLE encoding"),
        ("stub-xcf.xcf", stub(b"gimp xcf v011\x00"), "xcf", "GIMP XCF magic"),
        ("stub-dng.dng", tiff_stub(), "tiff", "DNG is TIFF-structured: sniffs as tiff (the `.dng` extension is what makes it RAW)"),
        ("stub-cr2.cr2", stub(b"II*\x00\x10\x00\x00\x00CR\x02\x00"), "raw:cr2", "Canon CR2: TIFF header + `CR` 02 00 at offset 8"),
        ("stub-nef.nef", stub(b"MM\x00\x2a\x00\x00\x00\x08"), "tiff", "Nikon NEF: big-endian TIFF header; sniffs as tiff"),
        ("stub-cr3.cr3", ftyp(b"crx "), "raw:cr3", "Canon CR3: ISOBMFF brand `crx `"),
        ("stub-psb.psb", stub(b"8BPS\x00\x02" + b"\x00" * 6), "psb", "Photoshop large document: 8BPS version 2"),
        ("stub-orf.orf", stub(b"IIRO\x08\x00\x00\x00"), "raw:orf", "Olympus ORF: IIRO"),
        ("stub-rw2.rw2", stub(b"IIU\x00\x18\x00\x00\x00"), "raw:rw2", "Panasonic RW2: II 55 00"),
        ("stub-raf.raf", stub(b"FUJIFILMCCD-RAW 0201"), "raw:raf", "Fujifilm RAF: FUJIFILMCCD-RAW"),
        ("stub-x3f.x3f", stub(b"FOVb\x00\x00\x03\x00"), "raw:x3f", "Sigma X3F: FOVb"),
        ("stub-mrw.mrw", stub(b"\x00MRM\x00\x00\x00\x00"), "raw:mrw", "Minolta MRW: 00 MRM"),
        ("stub-crw.crw", stub(bytes.fromhex("49491a0000004845415043434452")), "raw:crw", "Canon CRW (CIFF): II 1A 00 00 00 HEAPCCDR"),
        ("stub-random-text.txt", b"not an image, just text\n", "none", "Negative control: plain text must sniff as nothing"),
    ]
    for name, data, sniff, desc in S:
        add(name, data, sniff, "none", 0, 0, "-", "HEADER-ONLY STUB, not an image, never decodable. " + desc)


def main():
    lossy = "--lossy-with-ffmpeg" in sys.argv
    os.makedirs(OUT, exist_ok=True)
    gen_png(); gen_gif(); gen_webp(lossy); gen_jpeg(); gen_bmp_ico(); gen_simple(); gen_tiff(); gen_misc(); gen_stubs()
    names = [f[0] for f in FILES]
    assert len(set(names)) == len(names)
    for name, data, *_ in FILES:
        with open(os.path.join(OUT, name), "wb") as fh:
            fh.write(data)
    FILES.sort(key=lambda f: f[0])
    with open(os.path.join(OUT, "manifest.txt"), "w") as m:
        m.write("# Expected results for every file in this folder. Generated by scripts/gen-format-corpus.py.\n"
                "# file  sniff  decode  width  height  check\n"
                "#  sniff : MediaFormat.formatId FormatSniffer must return for the file's first 32 bytes, or `none`\n"
                "#  decode: `platform` = Android's own codecs must open it (device test); `none` = no decode is\n"
                "#          claimed (stubs, and valid files of formats the app does not decode yet)\n"
                "#  width/height: upright pixel size (0 for stubs); check: `quad` = corners are red/green/blue/yellow\n")
        for name, _, sniff, decode, w, h, check, _d in FILES:
            m.write(f"{name} {sniff} {decode} {w} {h} {check}\n")
    with open(os.path.join(OUT, "SOURCES.md"), "w") as s:
        s.write("# Format corpus sources and licence audit\n\n"
                "Every file here was generated by `scripts/gen-format-corpus.py` from a synthetic 4-quadrant picture "
                "(red, green, blue, yellow). No third-party image, sample file or photo is included. All files are "
                "our own work, released CC0 (public domain dedication). Regenerate with `python3 scripts/gen-format-corpus.py`. "
                "The single exception to \"script-built\" is `webp-lossy.webp`, encoded once by libwebp (via ffmpeg) from the same "
                "synthetic picture; the pixels are ours, the encoder's output is not a copy of anyone's sample.\n\n"
                "`stub-*` files are header-only: they hold a format's magic bytes so the sniffer can be tested, are not images, "
                "and are never claimed to be decodable. See docs/testdata-formats.md.\n\n"
                "| File | Origin / licence | Good for |\n|---|---|---|\n")
        for name, _, _, _, _, _, _, desc in FILES:
            s.write(f"| `{name}` | generated by our script, CC0 | {desc} |\n")
    total = sum(len(f[1]) for f in FILES)
    print(f"{len(FILES)} files, {total} bytes")


if __name__ == "__main__":
    main()
