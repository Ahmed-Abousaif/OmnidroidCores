#!/usr/bin/env python3
"""Generate or update OmnidroidCores root delivery manifest.json (and optional Ed25519 .sig).

Root manifest schemaVersion = 1 (distinct from per-core libretro-db manifest schemaVersion = 4).
Preserves existing older module releases up to --max-module-history so apps can fall back to the
newest compatible ABI major release and older apps can continue ignoring this file cleanly.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import pathlib
import sys
from typing import Any, Dict, List

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
CORES_ROOT = SCRIPT_DIR.parents[2]
sys.path.insert(0, str(CORES_ROOT))
sys.path.insert(0, str(SCRIPT_DIR))

from ed25519_tool import decode_key_b64, sign_bytes  # noqa: E402
from update_cores import CORES  # noqa: E402

SCHEMA_VERSION = 1


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def parse_systems_from_db_manifest(core_name: str) -> List[str]:
    db_manifest = CORES_ROOT / f"omnidroid_core_{core_name}" / "src" / "main" / "assets" / "libretro-db" / core_name / "manifest.json"
    if not db_manifest.is_file():
        return []
    try:
        data = json.loads(db_manifest.read_text(encoding="utf-8"))
        systems: List[str] = []
        for s in data.get("slices", []):
            for sys_id in s.get("systems", []):
                if sys_id not in systems:
                    systems.append(sys_id)
        return systems
    except Exception:
        return []


def build_cores_block(ra_meta_path: pathlib.Path) -> Dict[str, Any]:
    ra_meta_raw = json.loads(ra_meta_path.read_text(encoding="utf-8"))
    ra_cores = ra_meta_raw.get("cores", {})
    ra_schema = int(ra_meta_raw.get("schemaVersion", 1))
    out: Dict[str, Any] = {}
    for core_id, spec in sorted(CORES.items()):
        entry: Dict[str, Any] = {
            "file": spec.target_so_name,
            "supportedAbis": list(spec.supported_abis),
            "systems": parse_systems_from_db_manifest(core_id),
        }
        if core_id in ra_cores:
            ra = dict(ra_cores[core_id])
            ra["schemaVersion"] = ra_schema
            entry["ra"] = ra
        out[core_id] = entry
    return out


def semver_key(version: str) -> tuple:
    parts: List[int] = []
    for p in version.lstrip("v").split("."):
        digits = "".join(ch for ch in p if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts[:3])


def build_module_release(
    *,
    version: str,
    abi_major: int,
    abi_minor: int,
    rcheevos_version: str,
    min_app_version_code: int,
    release_tag: str,
    repo_slug: str,
    artifacts_dir: pathlib.Path,
    symbols_file: pathlib.Path | None,
    seed: bytes | None,
) -> Dict[str, Any]:
    artifacts: Dict[str, Any] = {}
    for abi_dir in sorted(artifacts_dir.iterdir()):
        if not abi_dir.is_dir():
            continue
        abi = abi_dir.name
        so_path = abi_dir / "libomnidroid_ra.so"
        if not so_path.is_file():
            continue
        raw = so_path.read_bytes()
        asset_name = f"libomnidroid_ra-android-{abi}.so"
        sig_b64 = base64.b64encode(sign_bytes(seed, raw)).decode("ascii") if seed else ""
        artifacts[abi] = {
            "file": "libomnidroid_ra.so",
            "url": f"https://github.com/{repo_slug}/releases/download/{release_tag}/{asset_name}",
            "size": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "sigEd25519": sig_b64,
        }

    if not artifacts:
        raise RuntimeError(f"No libomnidroid_ra.so artifacts found under {artifacts_dir}")

    entry: Dict[str, Any] = {
        "id": "omni_ra",
        "type": "module",
        "version": version,
        "abiMajor": abi_major,
        "abiMinor": abi_minor,
        "rcheevosVersion": rcheevos_version,
        "minAppVersionCode": min_app_version_code,
        "releaseTag": release_tag,
        "releaseNotesUrl": f"https://github.com/{repo_slug}/releases/tag/{release_tag}",
        "artifacts": artifacts,
    }
    if symbols_file and symbols_file.is_file():
        entry["symbols"] = {
            "url": f"https://github.com/{repo_slug}/releases/download/{release_tag}/{symbols_file.name}",
            "size": symbols_file.stat().st_size,
            "sha256": sha256_file(symbols_file),
        }
    return entry


def main() -> int:
    parser = argparse.ArgumentParser(description="Update OmnidroidCores root manifest.json")
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    parser.add_argument("--cores-version", default="3.2.0")
    parser.add_argument("--ra-meta", default=SCRIPT_DIR.parent / "core_ra_metadata.json", type=pathlib.Path)
    parser.add_argument("--repo-slug", default="Ahmed-Abousaif/OmnidroidCores")
    parser.add_argument("--module-version", default="")
    parser.add_argument("--abi-major", type=int, default=1)
    parser.add_argument("--abi-minor", type=int, default=0)
    parser.add_argument("--rcheevos-version", default="12.5.0")
    parser.add_argument("--min-app-version-code", type=int, default=320)
    parser.add_argument("--release-tag", default="")
    parser.add_argument("--artifacts-dir", type=pathlib.Path, default=None)
    parser.add_argument("--symbols-file", type=pathlib.Path, default=None)
    parser.add_argument("--max-module-history", type=int, default=6)
    parser.add_argument("--sign", action="store_true", help="Sign artifacts and manifest using seed env")
    parser.add_argument("--seed-env", default="OMNI_RA_ED25519_PRIVATE_KEY")
    args = parser.parse_args()

    seed: bytes | None = None
    if args.sign:
        seed_b64 = os.environ.get(args.seed_env, "").strip()
        if not seed_b64:
            print(f"Missing signing key env var {args.seed_env}", file=sys.stderr)
            return 2
        seed = decode_key_b64(seed_b64)

    existing: Dict[str, Any] = {}
    if args.manifest.is_file():
        existing = json.loads(args.manifest.read_text(encoding="utf-8"))

    modules_block: Dict[str, List[Dict[str, Any]]] = existing.get("modules", {})
    ra_releases: List[Dict[str, Any]] = [
        r for r in modules_block.get("omni_ra", [])
        if r.get("version") != args.module_version
    ]

    if args.module_version and args.artifacts_dir:
        tag = args.release_tag or f"ra-v{args.module_version}"
        rel = build_module_release(
            version=args.module_version,
            abi_major=args.abi_major,
            abi_minor=args.abi_minor,
            rcheevos_version=args.rcheevos_version,
            min_app_version_code=args.min_app_version_code,
            release_tag=tag,
            repo_slug=args.repo_slug,
            artifacts_dir=args.artifacts_dir,
            symbols_file=args.symbols_file,
            seed=seed,
        )
        ra_releases.append(rel)

    ra_releases.sort(key=lambda r: semver_key(str(r.get("version", "0.0.0"))), reverse=True)
    if args.max_module_history > 0:
        ra_releases = ra_releases[: args.max_module_history]
    modules_block["omni_ra"] = ra_releases

    manifest_obj = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "coresVersion": args.cores_version or existing.get("coresVersion", "3.2.0"),
        "modules": modules_block,
        "cores": build_cores_block(args.ra_meta),
    }

    encoded = (json.dumps(manifest_obj, indent=2, sort_keys=False) + "\n").encode("utf-8")
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_bytes(encoded)
    print(f"Wrote manifest: {args.manifest} ({len(encoded)} bytes)")

    if seed:
        sig_path = args.manifest.with_name(args.manifest.name + ".sig")
        sig_b64 = base64.b64encode(sign_bytes(seed, encoded)).decode("ascii")
        sig_path.write_text(sig_b64 + "\n", encoding="utf-8")
        print(f"Signed manifest: {sig_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
