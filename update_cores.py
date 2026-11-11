#!/usr/bin/env python3
"""
Omnidroid Core Management and Updater Tool
==========================================
Unified, cross-platform tool to download, verify, configure, and maintain
all emulator cores for Omnidroid.

Features:
- Pure Python 3 (cross-platform: Windows, macOS, Linux) with zero non-standard deps.
- Multi-threaded parallel downloads with automatic retries and exponential backoff.
- Declarative, extensible core registry: adding a new core takes 1 line of configuration.
- Automatic multi-ABI layout with 0-byte dummy fallback .so files for Google Play bundletool.
- Automatic scaffolding generation for AndroidManifest.xml and build.gradle.kts.
- Verification and audit mode to inspect core health.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import hashlib
import io
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from enum import Enum
from typing import Dict, List, Optional, Set, Tuple

from build_eden import build_eden

ALL_ABIS: Tuple[str, ...] = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
BUILDBOT_BASE = "https://buildbot.libretro.com/nightly/android/latest"


class SourceType(str, Enum):
    BUILDBOT = "buildbot"
    EDEN_SOURCE = "eden_source"
    CEMU = "cemu"
    LOCAL = "local"


@dataclasses.dataclass(frozen=True)
class CoreSpec:
    name: str
    system_title: str
    source: SourceType = SourceType.BUILDBOT
    upstream_name: str = ""
    supported_abis: Tuple[str, ...] = ALL_ABIS
    so_name: str = ""
    extra_libs: Tuple[str, ...] = ()
    namespace_override: str = ""
    keep_debug_symbols: str = "*/*/*_libretro_android.so"

    @property
    def target_so_name(self) -> str:
        if self.so_name:
            return self.so_name
        return f"lib{self.name}_libretro_android.so"

    @property
    def upstream_core_name(self) -> str:
        return self.upstream_name or self.name

    @property
    def namespace(self) -> str:
        if self.namespace_override:
            return self.namespace_override
        return f"com.omnidroid.core.{self.name}"

    @property
    def dummy_abis(self) -> Tuple[str, ...]:
        return tuple(abi for abi in ALL_ABIS if abi not in self.supported_abis)


# ---------------------------------------------------------------------------
# Declarative Core Registry
# ---------------------------------------------------------------------------
CORES: Dict[str, CoreSpec] = {
    # Atari
    "stella": CoreSpec("stella", "Atari 2600"),
    "prosystem": CoreSpec("prosystem", "Atari 7800"),
    "handy": CoreSpec("handy", "Atari Lynx"),
    # Nintendo Consoles & Handhelds
    "fceumm": CoreSpec("fceumm", "Nintendo Entertainment System (NES)"),
    "snes9x": CoreSpec("snes9x", "Super Nintendo Entertainment System (SNES)"),
    "gambatte": CoreSpec("gambatte", "Game Boy / Game Boy Color"),
    "mgba": CoreSpec("mgba", "Game Boy Advance"),
    "mupen64plus_next_gles3": CoreSpec("mupen64plus_next_gles3", "Nintendo 64"),
    "dolphin": CoreSpec("dolphin", "GameCube / Wii"),
    "melondsds": CoreSpec(
        "melondsds",
        "Nintendo DS",
        supported_abis=("arm64-v8a", "armeabi-v7a", "x86_64"),
    ),
    "citra": CoreSpec(
        "citra",
        "Nintendo 3DS (Citra)",
        source=SourceType.LOCAL,
        supported_abis=("arm64-v8a", "x86_64"),
    ),
    "azahar": CoreSpec(
        "azahar",
        "Nintendo 3DS (Azahar)",
        source=SourceType.LOCAL,
        supported_abis=("arm64-v8a",),
    ),
    "cemu": CoreSpec(
        "cemu",
        "Wii U (Cemu)",
        source=SourceType.CEMU,
        supported_abis=("arm64-v8a",),
        so_name="libcemu_libretro_android.so",
    ),
    "switch": CoreSpec(
        "switch",
        "Nintendo Switch (Eden)",
        source=SourceType.EDEN_SOURCE,
        supported_abis=("arm64-v8a",),
        so_name="libyuzu-android.so",
        extra_libs=(
            "libhook_impl.so",
            "libmain_hook.so",
            "libgsl_alloc_hook.so",
            "libfile_redirect_hook.so",
        ),
        namespace_override="com.omnidroid.core.switch_emu",
        keep_debug_symbols="*/*/libyuzu-android.so",
    ),
    # Sega
    "genesis_plus_gx": CoreSpec("genesis_plus_gx", "Sega Genesis / Master System / Game Gear / CD"),
    "flycast": CoreSpec("flycast", "Sega Dreamcast"),
    # Sony
    "pcsx_rearmed": CoreSpec("pcsx_rearmed", "Sony PlayStation 1"),
    "ppsspp": CoreSpec("ppsspp", "Sony PlayStation Portable (PSP)"),
    "armsx2": CoreSpec(
        "armsx2",
        "Sony PlayStation 2 (AetherSX2 / NetherSX2)",
        source=SourceType.LOCAL,
        supported_abis=("arm64-v8a",),
    ),
    # Arcade
    "fbneo": CoreSpec("fbneo", "FinalBurn Neo Arcade"),
    "mame2003_plus": CoreSpec("mame2003_plus", "MAME 2003-Plus Arcade"),
    # NEC & Bandai & SNK
    "mednafen_pce_fast": CoreSpec("mednafen_pce_fast", "PC Engine / TurboGrafx-16"),
    "mednafen_ngp": CoreSpec("mednafen_ngp", "Neo Geo Pocket / Color"),
    "mednafen_wswan": CoreSpec("mednafen_wswan", "WonderSwan / Color"),
    # DOS
    "dosbox_pure": CoreSpec("dosbox_pure", "DOSBox Pure (PC DOS)"),
}


# ---------------------------------------------------------------------------
# Templates for Dynamic Feature Scaffolding
# ---------------------------------------------------------------------------
MANIFEST_TEMPLATE = """<manifest xmlns:dist="http://schemas.android.com/apk/distribution"
    xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:hasCode="false"
        android:extractNativeLibs="true" />

    <dist:module dist:title="@string/core_name_{name}">
        <dist:delivery>
            <dist:on-demand />
            <dist:install-time>
                <dist:conditions>
                    <dist:device-feature dist:name="android.software.leanback"/>
                </dist:conditions>
            </dist:install-time>
        </dist:delivery>
        <dist:fusing dist:include="true" />
    </dist:module>
</manifest>
"""

GRADLE_TEMPLATE = """plugins {{
    id("com.android.dynamic-feature")
    id("kotlin-android")
    id("kotlin-kapt")
}}

android {{
    namespace = "{namespace}"

    defaultConfig {{
        missingDimensionStrategy("opensource", "play")
        missingDimensionStrategy("cores", "dynamic")
    }}

    packaging {{
        jniLibs {{
            useLegacyPackaging = true
            keepDebugSymbols += setOf("{keep_debug_symbols}")
        }}
    }}
}}

dependencies {{
    implementation(project(":omnidroid-app"))
    implementation(kotlin(deps.libs.kotlin.stdlib))
}}
"""


# ---------------------------------------------------------------------------
# Network & Download Utilities
# ---------------------------------------------------------------------------
def fetch_url(url: str, retries: int = 3, backoff: float = 1.0) -> bytes:
    headers = {
        "User-Agent": "Omnidroid-Core-Updater/2.6.0 (Android Emulation Core Sync)",
        "Accept-Encoding": "gzip",
    }
    req = urllib.request.Request(url, headers=headers)
    last_error: Optional[Exception] = None

    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = resp.read()
                if resp.headers.get("Content-Encoding") == "gzip":
                    import gzip
                    data = gzip.decompress(data)
                return data
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as e:
            last_error = e
            if attempt < retries - 1:
                time.sleep(backoff * (2**attempt))
    raise RuntimeError(f"Failed to fetch {url} after {retries} attempts: {last_error}")


def download_libretro_buildbot_core(
    spec: CoreSpec,
    abi: str,
    out_dir: pathlib.Path,
) -> Tuple[bool, str]:
    """Downloads a core zip from the Libretro buildbot and extracts the .so library."""
    zip_filename = f"{spec.upstream_core_name}_libretro_android.so.zip"
    url = f"{BUILDBOT_BASE}/{abi}/{zip_filename}"
    target_file = out_dir / spec.target_so_name

    try:
        raw_zip = fetch_url(url)
    except Exception as e:
        return False, f"HTTP Error: {e}"

    try:
        with zipfile.ZipFile(io.BytesIO(raw_zip)) as z:
            so_names = [n for n in z.namelist() if n.endswith(".so")]
            if not so_names:
                return False, f"No .so found inside {zip_filename}"
            so_data = z.read(so_names[0])
            out_dir.mkdir(parents=True, exist_ok=True)
            with open(target_file, "wb") as f:
                f.write(so_data)
            return True, f"{len(so_data):,} bytes"
    except Exception as e:
        return False, f"Zip error: {e}"


def get_github_token() -> Optional[str]:
    """Retrieves GitHub token from environment or gh CLI if authenticated."""
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        return token
    try:
        res = subprocess.run(
            ["gh", "auth", "token"],
            capture_output=True,
            text=True,
            check=True,
            timeout=5,
        )
        return res.stdout.strip()
    except Exception:
        return None


def download_github_release_asset(
    repo: str,
    tag: str,
    asset_name_filter: str,
    out_file: pathlib.Path,
) -> bool:
    """Downloads an asset from GitHub Releases supporting public/private repos and gh CLI."""
    token = get_github_token()

    # 1. Try GitHub API (works for both public and private repositories)
    tag_endpoint = (
        f"https://api.github.com/repos/{repo}/releases/tags/{tag}"
        if tag != "latest"
        else f"https://api.github.com/repos/{repo}/releases/latest"
    )
    headers = {
        "User-Agent": "Omnidroid-Core-Updater/2.6.0 (Android Emulation Core Sync)",
        "Accept": "application/vnd.github.v3+json",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    try:
        req = urllib.request.Request(tag_endpoint, headers=headers)
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode())
            for asset in data.get("assets", []):
                if asset_name_filter in asset["name"]:
                    asset_url = asset["url"]
                    dl_headers = {
                        "User-Agent": headers["User-Agent"],
                        "Accept": "application/octet-stream",
                    }
                    if token:
                        dl_headers["Authorization"] = f"Bearer {token}"
                    dl_req = urllib.request.Request(asset_url, headers=dl_headers)
                    with urllib.request.urlopen(dl_req, timeout=180) as dl_resp:
                        out_file.parent.mkdir(parents=True, exist_ok=True)
                        with open(out_file, "wb") as f:
                            shutil.copyfileobj(dl_resp, f)
                        if out_file.stat().st_size > 100_000:
                            return True
    except Exception:
        pass

    # 2. Try gh CLI fallback
    try:
        res = subprocess.run(
            [
                "gh", "release", "download", tag,
                "--repo", repo,
                "--pattern", f"*{asset_name_filter}*",
                "--dir", str(out_file.parent),
                "--clobber",
            ],
            capture_output=True,
            text=True,
            timeout=180,
        )
        if res.returncode == 0:
            candidates = list(out_file.parent.glob(f"*{asset_name_filter}*"))
            if candidates and candidates[0].stat().st_size > 100_000:
                if candidates[0] != out_file:
                    shutil.move(candidates[0], out_file)
                return True
    except Exception:
        pass

    # 3. Direct public URL fallback
    direct_urls = [
        f"https://github.com/{repo}/releases/download/{tag}/{asset_name_filter}",
        f"https://github.com/{repo}/releases/latest/download/{asset_name_filter}",
    ]
    for u in direct_urls:
        try:
            raw = fetch_url(u)
            if len(raw) > 100_000:
                out_file.parent.mkdir(parents=True, exist_ok=True)
                out_file.write_bytes(raw)
                return True
        except Exception:
            continue

    return False


def strip_binary_if_needed(so_path: pathlib.Path) -> bool:
    """Strips debug symbols if binary is larger than expected (> 35MB) and unstripped."""
    if not so_path.exists() or so_path.stat().st_size < 35_000_000:
        return False

    strip_candidates: List[pathlib.Path] = []
    for env_var in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"]:
        ndk_val = os.environ.get(env_var)
        if ndk_val:
            strip_candidates.extend(list(pathlib.Path(ndk_val).glob("**/llvm-strip*")))

    for env_var in ["ANDROID_HOME", "ANDROID_SDK_ROOT"]:
        sdk_val = os.environ.get(env_var)
        if sdk_val:
            strip_candidates.extend(list(pathlib.Path(sdk_val).glob("ndk/**/llvm-strip*")))

    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        strip_candidates.extend(list(pathlib.Path(local_app_data).glob("Android/Sdk/ndk/**/llvm-strip*")))

    for tool in strip_candidates:
        if tool.is_file():
            try:
                subprocess.run([str(tool), "--strip-unneeded", str(so_path)], check=True, timeout=30)
                return True
            except Exception:
                pass

    for name in ["llvm-strip", "strip"]:
        if shutil.which(name):
            try:
                subprocess.run([name, "--strip-unneeded", str(so_path)], check=True, timeout=30)
                return True
            except Exception:
                pass

    return False


def sync_cemu_core(
    spec: CoreSpec,
    cores_root: pathlib.Path,
    out_dir: pathlib.Path,
    cache_dir: Optional[pathlib.Path] = None,
    cemu_tag: str = "v0.1.0",
) -> Tuple[bool, str]:
    """Syncs Cemu core from GitHub Release, upstream build, or root binary cache."""
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / spec.target_so_name
    cache = cache_dir or (cores_root / ".cache")
    cache.mkdir(parents=True, exist_ok=True)

    # 1. Download release asset from GitHub (supporting public/private repos and gh CLI)
    zip_cache_path = cache / f"cemu-{cemu_tag}-libcemu_libretro_android.so.zip"
    if not zip_cache_path.exists() or zip_cache_path.stat().st_size < 1_000_000:
        download_github_release_asset(
            repo="Ahmed-Abousaif/cemu-libretro-android",
            tag=cemu_tag,
            asset_name_filter="libcemu_libretro_android.so.zip",
            out_file=zip_cache_path,
        )

    if zip_cache_path.exists() and zip_cache_path.stat().st_size > 1_000_000:
        try:
            with zipfile.ZipFile(zip_cache_path) as z:
                so_names = [n for n in z.namelist() if n.endswith(".so")]
                if so_names:
                    data = z.read(so_names[0])
                    with open(target, "wb") as f:
                        f.write(data)
                    strip_binary_if_needed(target)
                    return True, f"Downloaded from GitHub Release {cemu_tag} ({target.stat().st_size:,} bytes)"
        except Exception:
            pass

    # Direct .so asset fallback
    so_cache_path = cache / f"cemu-{cemu_tag}-libcemu_libretro_android.so"
    if not so_cache_path.exists() or so_cache_path.stat().st_size < 1_000_000:
        download_github_release_asset(
            repo="Ahmed-Abousaif/cemu-libretro-android",
            tag=cemu_tag,
            asset_name_filter="libcemu_libretro_android.so",
            out_file=so_cache_path,
        )

    if so_cache_path.exists() and so_cache_path.stat().st_size > 1_000_000:
        shutil.copy2(so_cache_path, target)
        strip_binary_if_needed(target)
        return True, f"Downloaded from GitHub Release {cemu_tag} ({target.stat().st_size:,} bytes)"

    # 2. Local candidate files
    candidates = [
        cores_root.parent / "modules" / "cemu" / "upstream" / "cemu-libretro-android" / "build_libretro_android_arm64" / "src" / "libretro" / "cemu_libretro.so",
        cores_root.parent / "modules" / "cemu" / "upstream" / "cemu-libretro-android" / "build_libretro_android_arm64" / "src" / "libretro" / "libcemu_libretro_android.so",
        cores_root / "libcemu_libretro_android.so",
        cores_root / "cemu_libretro_android.so",
    ]

    for c in candidates:
        if c.exists() and c.stat().st_size > 1_000_000:
            shutil.copy2(c, target)
            strip_binary_if_needed(target)
            return True, f"Copied from local {c.name} ({target.stat().st_size:,} bytes)"

    if target.exists() and target.stat().st_size > 1_000_000:
        strip_binary_if_needed(target)
        return True, f"Existing binary kept ({target.stat().st_size:,} bytes)"

    return False, "Failed to download Cemu from GitHub release and no local prebuilt binary found."


# ---------------------------------------------------------------------------
# Scaffolding & Multi-ABI Consistency Generator
# ---------------------------------------------------------------------------
def write_scaffolding(spec: CoreSpec, core_dir: pathlib.Path, dry_run: bool = False) -> None:
    """Generates AndroidManifest.xml and build.gradle.kts for the core dynamic feature module."""
    src_main = core_dir / "src" / "main"
    manifest_path = src_main / "AndroidManifest.xml"
    gradle_path = core_dir / "build.gradle.kts"

    manifest_content = MANIFEST_TEMPLATE.format(name=spec.name)
    gradle_content = GRADLE_TEMPLATE.format(
        namespace=spec.namespace,
        keep_debug_symbols=spec.keep_debug_symbols,
    )

    if not dry_run:
        src_main.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(manifest_content, encoding="utf-8")
        gradle_path.write_text(gradle_content, encoding="utf-8")


def ensure_dummy_fallback_abis(spec: CoreSpec, jni_dir: pathlib.Path, dry_run: bool = False) -> List[str]:
    """Ensures 0-byte dummy .so files exist for unsupported ABIs so bundletool is satisfied."""
    created: List[str] = []
    for abi in spec.dummy_abis:
        abi_dir = jni_dir / abi
        dummy_file = abi_dir / spec.target_so_name
        if not dummy_file.exists() or dummy_file.stat().st_size != 0:
            if not dry_run:
                abi_dir.mkdir(parents=True, exist_ok=True)
                dummy_file.write_bytes(b"")
            created.append(abi)
    return created


# ---------------------------------------------------------------------------
# Core Operations
# ---------------------------------------------------------------------------
def update_core(
    spec: CoreSpec,
    cores_root: pathlib.Path,
    selected_abis: Set[str],
    cache_dir: pathlib.Path,
    eden_ref: str,
    cemu_tag: str,
    skip_buildbot: bool,
    scaffold_only: bool,
    dry_run: bool,
    eden_jobs: int = 2,
) -> Dict[str, str]:
    """Updates a single core module: scaffolding, binaries, and dummy ABIs."""
    core_dir = cores_root / f"omnidroid_core_{spec.name}"
    jni_dir = core_dir / "src" / "main" / "jniLibs"
    results: Dict[str, str] = {}

    # 1. Scaffolding
    write_scaffolding(spec, core_dir, dry_run=dry_run)

    # 2. Dummy ABI fallback check
    dummy_created = ensure_dummy_fallback_abis(spec, jni_dir, dry_run=dry_run)
    if dummy_created:
        results["dummies"] = f"Created bundletool fallback ABIs: {', '.join(dummy_created)}"

    if scaffold_only:
        results["status"] = "Scaffolding generated"
        return results

    # 3. Binaries ingestion
    if spec.source == SourceType.BUILDBOT:
        if skip_buildbot:
            results["status"] = "Buildbot download skipped"
            return results

        target_abis = [abi for abi in spec.supported_abis if abi in selected_abis]
        for abi in target_abis:
            if dry_run:
                results[abi] = f"[DRY-RUN] Would fetch {spec.upstream_core_name} for {abi}"
                continue
            ok, msg = download_libretro_buildbot_core(spec, abi, jni_dir / abi)
            results[abi] = f"OK ({msg})" if ok else f"FAIL ({msg})"

    elif spec.source == SourceType.EDEN_SOURCE:
        if "arm64-v8a" in selected_abis:
            if dry_run:
                results["arm64-v8a"] = f"[DRY-RUN] Would build Eden {eden_ref} from source for arm64-v8a"
            else:
                try:
                    commit = build_eden(
                        cores_root.parent / "modules/switch/upstream/eden",
                        jni_dir / "arm64-v8a", ref=eden_ref, jobs=eden_jobs,
                    )
                    results["arm64-v8a"] = f"OK (Built Eden commit {commit})"
                except Exception as e:
                    results["arm64-v8a"] = f"FAIL (Error: {e})"

    elif spec.source == SourceType.CEMU:
        if "arm64-v8a" in selected_abis:
            if dry_run:
                results["arm64-v8a"] = f"[DRY-RUN] Would sync Cemu core ({cemu_tag}) for arm64-v8a"
            else:
                ok, msg = sync_cemu_core(spec, cores_root, jni_dir / "arm64-v8a", cache_dir=cache_dir, cemu_tag=cemu_tag)
                results["arm64-v8a"] = f"OK ({msg})" if ok else f"FAIL ({msg})"

    elif spec.source == SourceType.LOCAL:
        for abi in spec.supported_abis:
            target = jni_dir / abi / spec.target_so_name
            if target.exists() and target.stat().st_size > 0:
                results[abi] = f"LOCAL OK ({target.stat().st_size:,} bytes)"
            else:
                results[abi] = "LOCAL (Binary missing, manual build required)"

    return results


def verify_cores(cores_root: pathlib.Path) -> bool:
    """Verifies that all registered cores are properly structured on disk."""
    print("=" * 78)
    print(f"{'Core':<24} {'Source':<10} {'Supported ABIs':<25} {'Health'}")
    print("=" * 78)

    all_healthy = True
    for name, spec in sorted(CORES.items()):
        core_dir = cores_root / f"omnidroid_core_{name}"
        jni_dir = core_dir / "src" / "main" / "jniLibs"
        manifest_path = core_dir / "src" / "main" / "AndroidManifest.xml"
        gradle_path = core_dir / "build.gradle.kts"

        issues: List[str] = []
        if not manifest_path.exists():
            issues.append("missing manifest")
        if not gradle_path.exists():
            issues.append("missing build.gradle.kts")

        for abi in spec.supported_abis:
            so = jni_dir / abi / spec.target_so_name
            if not so.exists() or so.stat().st_size == 0:
                issues.append(f"missing {abi} binary")
            for library_name in spec.extra_libs:
                extra = jni_dir / abi / library_name
                if not extra.exists() or extra.stat().st_size == 0:
                    issues.append(f"missing {abi} {library_name}")

        for dummy_abi in spec.dummy_abis:
            dummy = jni_dir / dummy_abi / spec.target_so_name
            if not dummy.exists() or dummy.stat().st_size != 0:
                issues.append(f"missing dummy for {dummy_abi}")

        abis_str = ",".join(spec.supported_abis)
        if len(abis_str) > 24:
            abis_str = "all 4 ABIs"

        status_str = "OK" if not issues else f"WARN ({', '.join(issues)})"
        if issues:
            all_healthy = False

        print(f"{name:<24} {spec.source.value:<10} {abis_str:<25} {status_str}")

    print("=" * 78)
    return all_healthy


# ---------------------------------------------------------------------------
# CLI Main Entry Point
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(
        description="Omnidroid Core Management and Updater Tool",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "-c", "--cores",
        help="Comma-separated core names to update (default: all registered cores)",
    )
    parser.add_argument(
        "-a", "--abis",
        help=f"Comma-separated ABIs to target (default: {','.join(ALL_ABIS)})",
    )
    parser.add_argument(
        "-j", "--jobs",
        type=int,
        default=8,
        help="Maximum parallel worker threads (default: 8)",
    )
    parser.add_argument(
        "--eden-ref",
        default="master",
        help="Eden branch/tag/SHA to build (default: latest master; 'pinned' builds the checked-out commit)",
    )
    parser.add_argument("--eden-jobs", type=int, default=2, help="Parallel Eden compiler jobs (default: 2)")
    parser.add_argument(
        "--cemu-tag",
        default="v0.1.0",
        help="Cemu release tag from Ahmed-Abousaif/cemu-libretro-android (default: v0.1.0)",
    )
    parser.add_argument(
        "--scaffold-only",
        action="store_true",
        help="Generate/update manifests, build.gradle.kts, and dummy ABIs without downloading",
    )
    parser.add_argument(
        "--skip-buildbot",
        action="store_true",
        help="Skip downloading standard cores from Libretro buildbot",
    )
    parser.add_argument(
        "--verify",
        action="store_true",
        help="Verify the health and layout of all cores on disk and exit",
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="List all registered cores and configurations and exit",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Simulate the update operations without making changes to disk",
    )

    args = parser.parse_args()
    if args.jobs < 1 or args.eden_jobs < 1:
        parser.error("--jobs and --eden-jobs must be positive")

    # Determine paths
    script_dir = pathlib.Path(__file__).resolve().parent
    cores_root = script_dir if (script_dir / "omnidroid_core_snes9x").exists() else script_dir / "omnidroid-cores"
    cache_dir = cores_root / ".cache"

    if args.list:
        print("=" * 78)
        print(f"{'Core':<24} {'Source':<12} {'Supported ABIs':<25} {'System'}")
        print("=" * 78)
        for name, spec in sorted(CORES.items()):
            abis_str = ",".join(spec.supported_abis)
            print(f"{name:<24} {spec.source.value:<12} {abis_str:<25} {spec.system_title}")
        print("=" * 78)
        print(f"Total registered cores: {len(CORES)}")
        return 0

    if args.verify:
        healthy = verify_cores(cores_root)
        return 0 if healthy else 1

    # Filter target cores
    if args.cores:
        selected_names = [c.strip().lower() for c in args.cores.split(",") if c.strip()]
        for name in selected_names:
            if name not in CORES:
                print(f"Error: Unknown core '{name}'. Use --list to view valid cores.", file=sys.stderr)
                return 1
        target_cores = [CORES[name] for name in selected_names]
    else:
        target_cores = list(CORES.values())

    # Filter target ABIs
    if args.abis:
        selected_abis = set(a.strip() for a in args.abis.split(",") if a.strip())
        for a in selected_abis:
            if a not in ALL_ABIS:
                print(f"Error: Unknown ABI '{a}'. Valid: {', '.join(ALL_ABIS)}", file=sys.stderr)
                return 1
    else:
        selected_abis = set(ALL_ABIS)

    print(f"=== Omnidroid Core Manager ===")
    print(f"Target Cores ({len(target_cores)}): {', '.join(c.name for c in target_cores)}")
    print(f"Target ABIs: {', '.join(sorted(selected_abis))}")
    print(f"Workers: {args.jobs} | Dry-run: {args.dry_run}")
    print("-" * 60)

    start_time = time.time()

    def process_core(spec: CoreSpec):
        return spec.name, update_core(
            spec=spec,
            cores_root=cores_root,
            selected_abis=selected_abis,
            cache_dir=cache_dir,
            eden_ref=args.eden_ref,
            eden_jobs=args.eden_jobs,
            cemu_tag=args.cemu_tag,
            skip_buildbot=args.skip_buildbot,
            scaffold_only=args.scaffold_only,
            dry_run=args.dry_run,
        )

    failed = False
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
        future_map = {executor.submit(process_core, spec): spec for spec in target_cores}
        for future in concurrent.futures.as_completed(future_map):
            name, results = future.result()
            print(f"[{name}]")
            for key, val in results.items():
                print(f"   {key:<12}: {val}")
                failed = failed or val.startswith("FAIL")

    elapsed = time.time() - start_time
    print("-" * 60)
    print(f"All operations finished in {elapsed:.1f}s.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

