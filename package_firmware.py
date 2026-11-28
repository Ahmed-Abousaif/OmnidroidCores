#!/usr/bin/env python3
"""Create reproducible firmware ZIPs from pinned, checksum-verified upstream files."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import pathlib
import urllib.parse
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
CONFIG_PATH = ROOT / "firmware-packages.json"


def validate_package(data: bytes, entries: list[dict]) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        expected = {item["name"] for item in entries}
        if len(archive.infolist()) != len(expected) or set(archive.namelist()) != expected:
            raise ValueError("Unexpected or missing firmware archive entries")
        for entry in entries:
            payload = archive.read(entry["name"])
            if len(payload) != entry["size"] or hashlib.md5(payload).hexdigest() != entry["md5"]:
                raise ValueError(f"Invalid firmware: {entry['name']}")


def build_package(entries: list[dict], sources: dict) -> tuple[bytes, list[dict]]:
    records = []
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for entry in sorted(entries, key=lambda item: item["name"]):
            source = sources[entry["source"]]
            repo = source["repository"].removeprefix("https://github.com/")
            url = "https://raw.githubusercontent.com/" + repo + "/" + source["revision"] + "/" + urllib.parse.quote(entry["sourcePath"], safe="/")
            with urllib.request.urlopen(url, timeout=60) as response:
                payload = response.read(entry["size"] + 1)
            if len(payload) != entry["size"] or hashlib.md5(payload).hexdigest() != entry["md5"]:
                raise ValueError(f"Upstream firmware checksum mismatch: {entry['name']}: got {len(payload)} bytes, MD5 {hashlib.md5(payload).hexdigest()}; expected {entry['size']} bytes, {entry['md5']}")
            info = zipfile.ZipInfo(entry["name"], date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, payload, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
            records.append({**entry, "sourceUrl": url, "sha256": hashlib.sha256(payload).hexdigest()})
    data = output.getvalue()
    validate_package(data, entries)
    return data, records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Check existing ZIPs offline without downloading or changing files")
    args = parser.parse_args()
    config = json.loads(CONFIG_PATH.read_text())
    assets = ROOT / "assets"
    manifest_path = assets / "firmware-sources.json"
    if args.verify:
        manifest = json.loads(manifest_path.read_text())
        for name, entries in config["packages"].items():
            data = (assets / f"{name}.zip").read_bytes()
            validate_package(data, entries)
            if hashlib.sha256(data).hexdigest() != manifest["packages"][name]["sha256"]:
                raise ValueError(f"Archive checksum mismatch: {name}")
            print(f"{name}.zip: verified {len(entries)} firmware files")
        return
    # Verify every download before updating any published package.
    built = {name: build_package(entries, config["sources"]) for name, entries in config["packages"].items()}
    assets.mkdir(exist_ok=True)
    manifest = {"schemaVersion": 1, "sources": config["sources"], "packages": {}}
    for name, (data, records) in built.items():
        (assets / f"{name}.zip").write_bytes(data)
        manifest["packages"][name] = {"file": f"{name}.zip", "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "files": records}
        print(f"{name}.zip: {len(records)} verified files, {len(data):,} bytes")
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
