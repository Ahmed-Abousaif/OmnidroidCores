#!/usr/bin/env python3
"""Build, verify ELF hygiene, strip, and stage libomnidroid_ra.so for all Android ABIs."""

from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import subprocess
import sys
from typing import Sequence

MODULE_DIR = pathlib.Path(__file__).resolve().parent
CORES_ROOT = MODULE_DIR.parents[1]
SCRIPTS_DIR = MODULE_DIR / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

import verify_elf  # noqa: E402

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
PREFERRED_NDK = "29.0.14206865"


def find_sdk_root() -> pathlib.Path:
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(env_var)
        if val and pathlib.Path(val).is_dir():
            return pathlib.Path(val)
    local_sdk = pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
    if local_sdk.is_dir():
        return local_sdk
    raise RuntimeError("Android SDK not found; set ANDROID_HOME or ANDROID_SDK_ROOT")


def find_ndk(sdk: pathlib.Path, ndk_version: str = PREFERRED_NDK) -> pathlib.Path:
    preferred = sdk / "ndk" / ndk_version
    if (preferred / "build/cmake/android.toolchain.cmake").is_file():
        return preferred
    candidates = sorted((sdk / "ndk").glob("*"))
    for cand in reversed(candidates):
        if (cand / "build/cmake/android.toolchain.cmake").is_file():
            return cand
    raise RuntimeError(f"Android NDK not found under {sdk / 'ndk'}")


def find_cmake_and_ninja(sdk: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path]:
    exe = ".exe" if os.name == "nt" else ""
    cmake_dirs = sorted((sdk / "cmake").glob("*"))
    for cdir in reversed(cmake_dirs):
        cmake = cdir / "bin" / f"cmake{exe}"
        ninja = cdir / "bin" / f"ninja{exe}"
        if cmake.is_file() and ninja.is_file():
            return cmake, ninja
    raise RuntimeError(f"CMake + Ninja not found under {sdk / 'cmake'}")


def find_llvm_tool(ndk: pathlib.Path, name: str) -> pathlib.Path:
    exe = ".exe" if os.name == "nt" else ""
    matches = sorted((ndk / "toolchains/llvm/prebuilt").glob(f"*/bin/{name}{exe}"))
    if not matches:
        raise RuntimeError(f"{name}{exe} not found under {ndk}")
    return matches[-1]


def git_short_sha() -> str:
    proc = subprocess.run(
        ["git", "-C", str(CORES_ROOT), "rev-parse", "--short=12", "HEAD"],
        capture_output=True,
        text=True,
    )
    return proc.stdout.strip() if proc.returncode == 0 and proc.stdout.strip() else "unknown"


def build_all(abis: Sequence[str] = ABIS, version: str = "1.0.0") -> None:
    sdk = find_sdk_root()
    ndk = find_ndk(sdk)
    cmake, ninja = find_cmake_and_ninja(sdk)
    readelf = find_llvm_tool(ndk, "llvm-readelf")
    strip = find_llvm_tool(ndk, "llvm-strip")
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    sha = git_short_sha()

    jni_root = MODULE_DIR / "src" / "main" / "jniLibs"
    for abi in abis:
        build_dir = CORES_ROOT / "build" / f"android-{abi}"
        subprocess.run(
            [
                str(cmake),
                "-S",
                str(MODULE_DIR),
                "-B",
                str(build_dir),
                "-G",
                "Ninja",
                f"-DCMAKE_MAKE_PROGRAM={ninja}",
                f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
                f"-DANDROID_ABI={abi}",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                f"-DOMNI_RA_VERSION={version}",
                f"-DOMNI_RA_GIT_HASH={sha}",
            ],
            check=True,
        )
        subprocess.run([str(cmake), "--build", str(build_dir)], check=True)

        built_so = build_dir / "libomnidroid_ra.so"
        out_dir = jni_root / abi
        out_dir.mkdir(parents=True, exist_ok=True)
        staged_so = out_dir / "libomnidroid_ra.so"
        shutil.copy2(built_so, staged_so)
        subprocess.run([str(strip), "--strip-unneeded", str(staged_so)], check=True)
        verify_elf.verify_so(str(readelf), staged_so)
        print(f"[{abi}] Staged & verified {staged_so} ({staged_so.stat().st_size} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="1.0.0")
    parser.add_argument("--abis", default=",".join(ABIS))
    args = parser.parse_args()
    abis = [a.strip() for a in args.abis.split(",") if a.strip()]
    build_all(abis=abis, version=args.version)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
