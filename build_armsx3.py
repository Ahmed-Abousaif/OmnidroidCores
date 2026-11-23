#!/usr/bin/env python3
"""Build ARMSX3 emulation with Omnidroid's own host; no upstream app binary."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from build_eden import validate_library

CORES = Path(__file__).resolve().parent
ROOT = CORES.parent
SOURCE = ROOT / "modules/ps3/upstream/armsx3"
OUTPUT = CORES / "omnidroid_core_armsx3/src/main/jniLibs/arm64-v8a"
NDK_VERSION = "29.0.14206865"
CMAKE_VERSION = "3.30.5"
API = 33


def run(*args: str, **kwargs) -> str:
    print("+ " + subprocess.list2cmdline(list(args)), flush=True)
    return subprocess.run(args, check=True, text=True, **kwargs).stdout or ""


def build(source: Path = SOURCE, output: Path = OUTPUT, jobs: int = 2, build_root: Path | None = None) -> None:
    commit = run("git", "-C", str(source), "rev-parse", "HEAD", capture_output=True).strip()
    if run("git", "-C", str(source), "status", "--porcelain", "--untracked-files=no", capture_output=True).strip():
        raise RuntimeError("ARMSX3 source has tracked changes; use a clean pinned checkout")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    ndk = sdk / "ndk" / NDK_VERSION
    suffix = ".exe" if os.name == "nt" else ""
    # The toolchain is pinned for reproducible engine code and snapshots.
    candidates = sorted((sdk / "cmake").glob("*/bin/cmake" + suffix))
    cmake = next((item for item in candidates if item.parent.parent.name == CMAKE_VERSION), candidates[-1] if candidates else None)
    if cmake is None or not (ndk / "build/cmake/android.toolchain.cmake").is_file():
        raise RuntimeError(f"Install NDK {NDK_VERSION} and CMake >= 3.30 in ANDROID_HOME")
    build_root = build_root or ROOT / "build/ps3" / commit / "engine-v1"
    if os.name == "nt":
        raise RuntimeError("Build the engine-only target on Linux (FFmpeg requires the POSIX toolchain). Upstream application-host artifacts are not accepted.")
    run("git", "-C", str(source), "submodule", "update", "--init", "--recursive", "--depth", "1")
    # These dependencies are not upstream submodules. Mirror its CI's exact pins.
    rashader_script = (source / "ios/scripts/build-librashader-ios.sh").read_text(encoding="utf-8")
    rashader_pin = re.search(r"^PIN=(.+)$", rashader_script, re.MULTILINE).group(1).strip('"\'')
    for repository, directory, pin in [
        ("https://github.com/SnowflakePowered/librashader.git", source / "3rdparty/librashader", rashader_pin),
    ]:
        if not (directory / ".git").exists():
            directory.mkdir(parents=True, exist_ok=True)
            run("git", "-C", str(directory), "init")
        run("git", "-C", str(directory), "fetch", "--depth", "1", repository, pin)
        run("git", "-C", str(directory), "checkout", "--detach", "FETCH_HEAD")
    staged_source = build_root / "source"
    run("python3", str(ROOT / "modules/ps3/engine/stage_project.py"), str(source), str(staged_source))
    core_build = build_root / "core"
    env = dict(os.environ, NDK_VERSION=NDK_VERSION, CMAKE_VERSION=cmake.parent.parent.name,
               ANDROID_API=str(API), BUILD_DIR=str(core_build), ARM_MARCH="armv8.2-a+dotprod+fp16")
    run("bash", str(staged_source / "android/configure.sh"), "-DANDROID_STL=c++_static",
        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", env=env)
    run(str(cmake), "--build", str(core_build), "--target", "omnidroid-ps3-engine", "--parallel", str(jobs))
    core_library = core_build / "omnidroid-engine/libomnidroid-armsx3.so"
    nm = next((ndk / "toolchains/llvm/prebuilt").glob("*/bin/llvm-nm" + suffix))
    run("python3", str(ROOT / "modules/ps3/engine/verify_boundary.py"),
        "--compile-commands", str(core_build / "compile_commands.json"),
        "--library", str(core_library), "--nm", str(nm))
    stage_library(core_library, output, commit, ndk)
    shutil.copy2(source / "LICENSE", ROOT / "omnidroid-app/src/main/assets/ps3/LICENSE")


def stage_library(engine: Path, output: Path, commit: str, ndk: Path) -> None:
    """Stage the merged engine/JNI library and its build provenance."""
    suffix = ".exe" if os.name == "nt" else ""
    name = "libomnidroid-armsx3.so"
    validate_library(engine)
    output.mkdir(parents=True, exist_ok=True)
    strip = next((ndk / "toolchains/llvm/prebuilt").glob("*/bin/llvm-strip" + suffix))
    with tempfile.TemporaryDirectory(dir=output.parent, prefix=".armsx3-") as temporary:
        staged = Path(temporary) / name
        shutil.copy2(engine, staged)
        run(str(strip), "--strip-unneeded", str(staged))
        digest = hashlib.sha256(staged.read_bytes()).hexdigest()
        os.replace(staged, output / name)
    metadata = {"repository": "https://github.com/ARMSX2/ARMSX3", "commit": commit,
                "ndk": NDK_VERSION, "android_api": API, "abi": "arm64-v8a",
                "library": name, "sha256": digest,
                "profile": "omnidroid-engine-only", "host_abi_version": 1, "packaging": "single-library"}
    (output.parents[1] / "armsx3-build.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--build-root", type=Path, help="Native Linux build directory, useful with Docker volumes")
    args = parser.parse_args()
    build(args.source.resolve(), args.output.resolve(), args.jobs,
          args.build_root.resolve() if args.build_root else None)
