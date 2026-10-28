#!/usr/bin/env python3
"""Independent reference for the Fotoz backup format v1 (docs/adr/ADR-014-encrypted-backup.md).

Written against the format description and the published Tink AES-GCM-HKDF streaming layout, NOT
against the Kotlin code, so BackupFormatTest's golden vector proves the format can be produced
and read outside this codebase. Needs only the `cryptography` package:

    python3 -I golden_vector.py

It prints the values pasted into BackupFormatTest (master key, header, whole file). Every
"random" input is fixed here; the real writer takes them from SecureRandom.
"""
import hashlib
import hmac
import struct
import unicodedata

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"FOTZBKP\x00"
VERSION = 1
KIND_PACK = 1
KDF_PBKDF2_SHA256 = 1

# Typed with a decomposed e + U+0301 and u + U+0308 on purpose: the format normalises to NFC first.
PASSPHRASE = "Café Zürich 日本語 #7"
ITERATIONS = 100_000
SET_SALT = bytes(range(0x10, 0x20))
SEGMENT = 128  # ciphertext segment size, deliberately tiny so 200 bytes span three segments
TINK_SALT = bytes(range(0xA0, 0xC0))  # 32 bytes, random per file in the real writer
NONCE_PREFIX = bytes(range(0xB0, 0xB7))  # 7 bytes, random per file in the real writer
PLAINTEXT = bytes((i * 13 + 5) & 0xFF for i in range(200))


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm, block, counter = b"", b"", 1
    while len(okm) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        okm += block
        counter += 1
    return okm[:length]


def header(kind: int, iterations: int, set_salt: bytes, segment: int) -> bytes:
    return MAGIC + struct.pack(">HBBI", VERSION, kind, KDF_PBKDF2_SHA256, iterations) + set_salt + struct.pack(">I", segment)


def seal(plaintext: bytes, master: bytes, hdr: bytes, segment: int) -> bytes:
    key = hkdf_sha256(master, TINK_SALT, hdr, 32)  # the 36 header bytes are the HKDF info
    tink_header = bytes([1 + len(TINK_SALT) + len(NONCE_PREFIX)]) + TINK_SALT + NONCE_PREFIX
    first_capacity = segment - len(tink_header) - 16
    later_capacity = segment - 16
    pieces, rest, capacity = [], plaintext, first_capacity
    while len(rest) > capacity:
        pieces.append(rest[:capacity])
        rest = rest[capacity:]
        capacity = later_capacity
    pieces.append(rest)  # the last piece is flagged final and may be empty only for empty input
    out = tink_header
    for index, piece in enumerate(pieces):
        final = 1 if index == len(pieces) - 1 else 0
        nonce = NONCE_PREFIX + struct.pack(">IB", index, final)
        out += AESGCM(key).encrypt(nonce, piece, None)  # no per-segment AAD
    return out


if __name__ == "__main__":
    password = unicodedata.normalize("NFC", PASSPHRASE).encode("utf-8")
    master = hashlib.pbkdf2_hmac("sha256", password, SET_SALT, ITERATIONS, 32)
    hdr = header(KIND_PACK, ITERATIONS, SET_SALT, SEGMENT)
    sealed = seal(PLAINTEXT, master, hdr, SEGMENT)
    print("passphrase NFC utf8 :", password.hex())
    print("master              :", master.hex())
    print("header (36 bytes)   :", hdr.hex())
    print("file                :", (hdr + sealed).hex())
    print("file length         :", len(hdr + sealed))
