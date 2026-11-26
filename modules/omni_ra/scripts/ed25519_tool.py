#!/usr/bin/env python3
"""Ed25519 keygen, detached signature creation, and signature verification.

Keys are raw 32-byte RFC 8032 seeds / public keys (Base64-encoded), matching
Google Tink Ed25519Verify(byte[32]) cleanly with zero ASN.1/PEM wrappers.
Uses cryptography if installed, falling back to pure-Python RFC 8032.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import pathlib
import sys
from typing import Tuple

try:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import (
        Ed25519PrivateKey,
        Ed25519PublicKey,
    )
    from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat

    _HAS_CRYPTOGRAPHY = True
except ImportError:
    _HAS_CRYPTOGRAPHY = False

# --- Pure-Python RFC 8032 Ed25519 fallback ---
_Q = 2**255 - 19
_L = 2**252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, -1, _Q) % _Q
_I = pow(2, (_Q - 1) // 4, _Q)
_BY = 4 * pow(5, -1, _Q) % _Q
_BX = None


def _xrecover(y: int) -> int:
    xx = (y * y - 1) * pow(_D * y * y + 1, -1, _Q)
    x = pow(xx, (_Q + 3) // 8, _Q)
    if (x * x - xx) % _Q != 0:
        x = (x * _I) % _Q
    if x % 2 != 0:
        x = _Q - x
    return x


_BX = _xrecover(_BY)
_B = (_BX % _Q, _BY % _Q, 1, (_BX * _BY) % _Q)


def _edwards_add(p: Tuple[int, int, int, int], q: Tuple[int, int, int, int]) -> Tuple[int, int, int, int]:
    x1, y1, z1, t1 = p
    x2, y2, z2, t2 = q
    a = ((y1 - x1) * (y2 - x2)) % _Q
    b = ((y1 + x1) * (y2 + x2)) % _Q
    c = (2 * t1 * t2 * _D) % _Q
    d = (2 * z1 * z2) % _Q
    e = b - a
    f = d - c
    g = d + c
    h = b + a
    return ((e * f) % _Q, (g * h) % _Q, (f * g) % _Q, (e * h) % _Q)


def _scalarmult(p: Tuple[int, int, int, int], e: int) -> Tuple[int, int, int, int]:
    res = (0, 1, 1, 0)
    cur = p
    while e > 0:
        if e & 1:
            res = _edwards_add(res, cur)
        cur = _edwards_add(cur, cur)
        e >>= 1
    return res


def _encodepoint(p: Tuple[int, int, int, int]) -> bytes:
    x, y, z, _ = p
    zi = pow(z, -1, _Q)
    xp = (x * zi) % _Q
    yp = (y * zi) % _Q
    return (yp | ((xp & 1) << 255)).to_bytes(32, "little")


def _decodepoint(s: bytes) -> Tuple[int, int, int, int]:
    y = int.from_bytes(s, "little")
    x_bit = y >> 255
    y &= (1 << 255) - 1
    if y >= _Q:
        raise ValueError("Invalid point y")
    x = _xrecover(y)
    if x == 0 and x_bit == 1:
        raise ValueError("Invalid negative zero x")
    if (x & 1) != x_bit:
        x = _Q - x
    p = (x, y, 1, (x * y) % _Q)
    if (-x * x + y * y - 1 - _D * x * x * y * y) % _Q != 0:
        raise ValueError("Point not on curve")
    return p


def _clamp_scalar(seed: bytes) -> Tuple[int, bytes]:
    h = hashlib.sha512(seed).digest()
    a_bytes = bytearray(h[:32])
    a_bytes[0] &= 248
    a_bytes[31] &= 63
    a_bytes[31] |= 64
    return int.from_bytes(a_bytes, "little"), h[32:]


def public_from_seed(seed: bytes) -> bytes:
    if len(seed) != 32:
        raise ValueError("Ed25519 private seed must be 32 bytes")
    if _HAS_CRYPTOGRAPHY:
        priv = Ed25519PrivateKey.from_private_bytes(seed)
        return priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    a, _ = _clamp_scalar(seed)
    return _encodepoint(_scalarmult(_B, a))


def sign_bytes(seed: bytes, data: bytes) -> bytes:
    if len(seed) != 32:
        raise ValueError("Ed25519 private seed must be 32 bytes")
    if _HAS_CRYPTOGRAPHY:
        return Ed25519PrivateKey.from_private_bytes(seed).sign(data)
    a, prefix = _clamp_scalar(seed)
    pk = _encodepoint(_scalarmult(_B, a))
    r = int.from_bytes(hashlib.sha512(prefix + data).digest(), "little") % _L
    r_pt = _encodepoint(_scalarmult(_B, r))
    h_ram = int.from_bytes(hashlib.sha512(r_pt + pk + data).digest(), "little") % _L
    s = (r + h_ram * a) % _L
    return r_pt + s.to_bytes(32, "little")


def verify_bytes(pubkey: bytes, signature: bytes, data: bytes) -> bool:
    if len(pubkey) != 32 or len(signature) != 64:
        return False
    if _HAS_CRYPTOGRAPHY:
        try:
            Ed25519PublicKey.from_public_bytes(pubkey).verify(signature, data)
            return True
        except InvalidSignature:
            return False
    try:
        a_pt = _decodepoint(pubkey)
        r_bytes = signature[:32]
        r_pt = _decodepoint(r_bytes)
        s = int.from_bytes(signature[32:], "little")
        if s >= _L:
            return False
        h = int.from_bytes(hashlib.sha512(r_bytes + pubkey + data).digest(), "little") % _L
        lhs = _scalarmult(_B, s)
        rhs = _edwards_add(r_pt, _scalarmult(a_pt, h))
        return _encodepoint(lhs) == _encodepoint(rhs)
    except Exception:
        return False


def decode_key_b64(value: str) -> bytes:
    return base64.b64decode(value.strip())


def main() -> int:
    parser = argparse.ArgumentParser(description="Ed25519 signing/verification utility")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("keygen", help="Generate a raw 32-byte Ed25519 keypair (base64)")

    p_sign = sub.add_parser("sign", help="Sign file producing detached base64/binary sig")
    p_sign.add_argument("--seed-env", default="OMNI_RA_ED25519_PRIVATE_KEY")
    p_sign.add_argument("--input", required=True)
    p_sign.add_argument("--output", required=True)

    p_ver = sub.add_parser("verify", help="Verify file against public key and .sig")
    p_ver.add_argument("--pubkey-b64", required=True)
    p_ver.add_argument("--input", required=True)
    p_ver.add_argument("--sig", required=True)

    args = parser.parse_args()
    if args.cmd == "keygen":
        seed = os.urandom(32)
        pub = public_from_seed(seed)
        print(f"OMNI_RA_ED25519_PRIVATE_KEY={base64.b64encode(seed).decode('ascii')}")
        print(f"OMNI_RA_ED25519_PUBLIC_KEY={base64.b64encode(pub).decode('ascii')}")
        return 0
    if args.cmd == "sign":
        seed_b64 = os.environ.get(args.seed_env, "").strip()
        if not seed_b64:
            print(f"Missing {args.seed_env}", file=sys.stderr)
            return 2
        data = pathlib.Path(args.input).read_bytes()
        sig = sign_bytes(decode_key_b64(seed_b64), data)
        pathlib.Path(args.output).write_text(base64.b64encode(sig).decode("ascii") + "\n", encoding="utf-8")
        return 0
    if args.cmd == "verify":
        data = pathlib.Path(args.input).read_bytes()
        sig_raw = pathlib.Path(args.sig).read_text(encoding="utf-8").strip()
        ok = verify_bytes(decode_key_b64(args.pubkey_b64), base64.b64decode(sig_raw), data)
        if not ok:
            print("Signature verification FAILED", file=sys.stderr)
            return 1
        print("Signature verified OK")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
