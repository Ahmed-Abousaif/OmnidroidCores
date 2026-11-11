#!/usr/bin/env python3
"""Build Eden's Android native libraries from a resolved upstream commit."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

EDEN_URL = "https://git.eden-emu.dev/eden-emu/eden.git"
LIBRARIES = (
    "libyuzu-android.so", "libhook_impl.so", "libmain_hook.so",
    "libgsl_alloc_hook.so", "libfile_redirect_hook.so",
)
NDK_VERSION = "30.0.16248370"
CMAKE_VERSION = "3.31.6"
ANDROID_API = 26
CORES_ROOT = Path(__file__).resolve().parent
DEFAULT_SOURCE = CORES_ROOT.parent / "modules/switch/upstream/eden"
DEFAULT_OUTPUT = CORES_ROOT / "omnidroid_core_switch/src/main/jniLibs/arm64-v8a"


def run(command: list[str], **kwargs) -> str:
    print("+ " + subprocess.list2cmdline([str(arg) for arg in command]), flush=True)
    result = subprocess.run(command, check=True, text=True, **kwargs)
    return (result.stdout or "").strip()


def git(source: Path, *args: str) -> str:
    return run(["git", "-C", str(source), *args], stdout=subprocess.PIPE)


def update_source(source: Path, ref: str = "master") -> str:
    """Resolve once and detach at that SHA; 'pinned' never fetches or checks out."""
    if not (source / ".git").exists():
        raise RuntimeError(f"Initialize Eden first: git submodule update --init {source}")
    if git(source, "status", "--porcelain", "--untracked-files=all"):
        raise RuntimeError("Eden has local changes. Refusing to overwrite the upstream source.")
    if ref != "pinned":
        if ref.startswith("-"):
            raise ValueError("Eden ref must be a branch, tag, or commit")
        git(source, "fetch", "--no-tags", "origin", ref)
        commit = git(source, "rev-parse", "FETCH_HEAD^{commit}")
        git(source, "checkout", "--detach", commit)
    commit = git(source, "rev-parse", "HEAD")
    for relative in ("CMakeLists.txt", "src/common/android/id_cache.cpp"):
        if not (source / relative).is_file():
            raise RuntimeError(f"Eden is missing {relative}")
    print(f"Eden source commit: {commit}", flush=True)
    return commit


def sdk_tools(source: Path) -> tuple[Path, Path, Path]:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("Set ANDROID_HOME to your Android SDK directory")
    # Follow the toolchain pinned by the selected Eden commit.
    gradle = (source / "src/android/app/build.gradle.kts").read_text(encoding="utf-8")
    ndk_version = re.search(r'ndkVersion\s*=\s*"([^"]+)"', gradle)
    cmake_version = re.search(r'cmake\s*\{\s*version\s*=\s*"([^"]+)"', gradle)
    ndk = Path(sdk) / "ndk" / (ndk_version.group(1) if ndk_version else NDK_VERSION)
    suffix = ".exe" if os.name == "nt" else ""
    cmake_bin = Path(sdk) / "cmake" / (cmake_version.group(1) if cmake_version else CMAKE_VERSION) / "bin"
    cmake = cmake_bin / f"cmake{suffix}"
    ninja = cmake_bin / f"ninja{suffix}"
    if not (ndk / "build/cmake/android.toolchain.cmake").is_file():
        raise RuntimeError(f"Missing NDK: {ndk}. Install Eden's ndkVersion with sdkmanager.")
    if not cmake.is_file() or not ninja.is_file():
        raise RuntimeError(f"Missing CMake/Ninja: {cmake_bin}. Install cmake;{cmake_bin.parent.name} with sdkmanager.")
    return ndk, cmake, ninja


def validate_library(path: Path) -> None:
    with path.open("rb") as binary:
        header = binary.read(20)
    if (len(header) < 20 or header[:6] != b"\x7fELF\x02\x01"
            or int.from_bytes(header[16:18], "little") != 3
            or int.from_bytes(header[18:20], "little") != 183):
        raise RuntimeError(f"Expected an Android AArch64 shared library: {path}")


def stage_libraries(build: Path, source: Path, output: Path, commit: str, ndk: Path,
                    base_hooks: Path | None = None) -> None:
    """Validate the complete set before replacing any packaged library."""
    libraries = {}
    for name in LIBRARIES:
        candidates = list((build / "lib").glob(name)) or list(build.rglob(name))
        if len(candidates) != 1:
            raise RuntimeError(f"Expected exactly one built {name}, found {len(candidates)}")
        validate_library(candidates[0])
        libraries[name] = candidates[0]
    sdl_java = source / "externals/generated/sdl/java/org/libsdl/app"
    if not (sdl_java / "SDL.java").is_file():
        raise RuntimeError(f"SDL Java bridge missing: {sdl_java}")
    cache = (build / "CMakeCache.txt").read_text(encoding="utf-8")
    sdl_root = re.search(r"^CPM_PACKAGE_SDL3_SOURCE_DIR:INTERNAL=(.+)$", cache, re.MULTILINE)
    if not sdl_root:
        raise RuntimeError("SDL3 source directory is missing from CMakeCache.txt")
    sdl_license = Path(sdl_root.group(1).strip()) / "include/SDL3/SDL_copying.h"
    if not sdl_license.is_file():
        raise RuntimeError(f"SDL license missing: {sdl_license}")
    strip = next((ndk / "toolchains/llvm/prebuilt").glob("*/bin/llvm-strip*"))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent, prefix=".eden-") as temporary:
        staged = Path(temporary)
        for name, path in libraries.items():
            shutil.copy2(path, staged / name)
            run([str(strip), "--strip-unneeded", str(staged / name)])
        metadata = {
            "repository": EDEN_URL, "commit": commit, "abi": "arm64-v8a",
            "ndk": ndk.name, "android_api": ANDROID_API, "configuration": "Release",
            "sha256": {name: hashlib.sha256((staged / name).read_bytes()).hexdigest() for name in libraries},
        }
        # Source files live outside src/main/java because the dynamic feature hasCode=false.
        java_output = output.parents[1] / "sdl/java/org/libsdl/app"
        for old in java_output.glob("*.java"):
            if not (sdl_java / old.name).is_file():
                old.unlink()
        shutil.copytree(sdl_java, java_output, dirs_exist_ok=True)
        shutil.copy2(sdl_license, output.parents[1] / "sdl/LICENSE.txt")
        if base_hooks is not None:
            base_hooks.mkdir(parents=True, exist_ok=True)
            for name in LIBRARIES[1:]:
                shutil.copy2(staged / name, base_hooks / name)
        output.mkdir(parents=True, exist_ok=True)
        for name in libraries:
            os.replace(staged / name, output / name)
        (output.parents[1] / "eden-build.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


def build_eden(source: Path, output: Path, ref: str = "master", jobs: int = 2) -> str:
    source, output = source.resolve(), output.resolve()
    commit = update_source(source, ref)
    ndk, cmake, ninja = sdk_tools(source)
    shader_compiler = shutil.which("glslangValidator") or shutil.which("glslang")
    if not shader_compiler and os.environ.get("VULKAN_SDK"):
        candidate = Path(os.environ["VULKAN_SDK"]) / "Bin/glslangValidator.exe"
        if candidate.is_file():
            shader_compiler = str(candidate)
    if not shader_compiler:
        raise RuntimeError("Install glslangValidator (Vulkan SDK on Windows, glslang-tools on Linux) and add it to PATH")
    # Separate build trees prevent stale outputs from a previous commit being packaged.
    build = CORES_ROOT / ".cache/eden" / commit / ndk.name
    build.mkdir(parents=True, exist_ok=True)
    run([
        str(cmake), "-S", str(source), "-B", str(build), "-G", "Ninja",
        f"-DCMAKE_MAKE_PROGRAM={ninja.as_posix()}",
        "-DCMAKE_NINJA_FORCE_RESPONSE_FILE=ON",
        f"-DGLSLANGVALIDATOR={Path(shader_compiler).as_posix()}",
        f"-DCMAKE_TOOLCHAIN_FILE={(ndk / 'build/cmake/android.toolchain.cmake').as_posix()}",
        "-DANDROID_ABI=arm64-v8a", f"-DANDROID_PLATFORM=android-{ANDROID_API}",
        "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_LIBRARY_OUTPUT_DIRECTORY={(build / 'lib').as_posix()}",
        "-DENABLE_QT=OFF", "-DENABLE_WEB_SERVICE=ON",
        "-DCPMUTIL_FORCE_BUNDLED=ON", "-DYUZU_USE_BUNDLED_FFMPEG=ON",
        "-DYUZU_USE_BUNDLED_SDL3=ON", "-DANDROID_ARM_NEON=ON",
        "-DBUILD_TESTING=OFF", "-DYUZU_TESTS=OFF", "-DDYNARMIC_TESTS=OFF",
        "-DENABLE_UPDATE_CHECKER=OFF", "-DYUZU_DOWNLOAD_ANDROID_VVL=OFF",
    ])
    run([str(cmake), "--build", str(build), "--parallel", str(jobs), "--target",
         "yuzu-android", "hook_impl", "main_hook", "gsl_alloc_hook", "file_redirect_hook"])
    base_hooks = CORES_ROOT.parent / "omnidroid-app/src/main/jniLibs/arm64-v8a"
    stage_libraries(build, source, output, commit, ndk,
                    base_hooks=base_hooks if base_hooks.is_dir() else None)
    print(f"Built and packaged Eden {commit} in {output}", flush=True)
    return commit


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="master", help="Branch/tag/SHA to fetch, or 'pinned' to build the current checkout")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--update-only", action="store_true")
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    try:
        if args.update_only:
            update_source(args.source.resolve(), args.ref)
        else:
            build_eden(args.source, args.output, args.ref, args.jobs)
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Eden build failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
