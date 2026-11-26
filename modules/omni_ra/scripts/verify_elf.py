#!/usr/bin/env python3
"""Verify compiled libomnidroid_ra.so ELF hygiene:
1. NEEDED libraries are strictly a subset of {libc.so, libm.so, libdl.so}.
2. All PT_LOAD segments have p_align >= 0x4000 (16 KB page alignment).
3. All exported GLOBAL FUNC symbols start with omni_ra_ (plus known C runtime stubs),
   and every required ABI symbol in omni_ra.h is present.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
from typing import List, Set

ALLOWED_NEEDED: Set[str] = {"libc.so", "libm.so", "libdl.so"}
ALLOWED_CRT_SYMS: Set[str] = {"__bss_start", "_edata", "_end", "_fini", "_init"}
REQUIRED_EXPORTS: Set[str] = {
    "omni_ra_abi_version",
    "omni_ra_get_build_info",
    "omni_ra_create",
    "omni_ra_destroy",
    "omni_ra_set_user_agent",
    "omni_ra_set_host",
    "omni_ra_set_log_level",
    "omni_ra_set_hardcore",
    "omni_ra_get_hardcore",
    "omni_ra_set_encore_mode",
    "omni_ra_set_unofficial_enabled",
    "omni_ra_set_spectator_mode",
    "omni_ra_is_system_allowed",
    "omni_ra_is_setting_allowed",
    "omni_ra_login_with_password",
    "omni_ra_login_with_token",
    "omni_ra_logout",
    "omni_ra_get_user_info",
    "omni_ra_cancel_async",
    "omni_ra_http_complete",
    "omni_ra_set_memory_map",
    "omni_ra_dump_memory_regions",
    "omni_ra_begin_identify_and_load_game",
    "omni_ra_begin_load_game_hash",
    "omni_ra_begin_identify_and_change_media",
    "omni_ra_unload_game",
    "omni_ra_is_game_loaded",
    "omni_ra_get_load_state",
    "omni_ra_do_frame",
    "omni_ra_idle",
    "omni_ra_reset",
    "omni_ra_is_processing_required",
    "omni_ra_can_pause",
    "omni_ra_progress_size",
    "omni_ra_serialize_progress",
    "omni_ra_deserialize_progress",
    "omni_ra_get_game_info",
    "omni_ra_get_user_game_summary",
    "omni_ra_has_rich_presence",
    "omni_ra_get_rich_presence_message",
    "omni_ra_get_achievement_list",
    "omni_ra_free_achievement_list",
    "omni_ra_get_leaderboard_list",
    "omni_ra_free_leaderboard_list",
}


def run_readelf(readelf: str, args: List[str], so_path: pathlib.Path) -> str:
    proc = subprocess.run(
        [readelf, *args, str(so_path)],
        capture_output=True,
        text=True,
        check=True,
    )
    return proc.stdout


def verify_so(readelf: str, so_path: pathlib.Path) -> None:
    if not so_path.is_file():
        raise AssertionError(f"Missing binary: {so_path}")

    dyn_out = run_readelf(readelf, ["-d"], so_path)
    needed = re.findall(r"\(NEEDED\)\s+Shared library:\s+\[([^\]]+)\]", dyn_out)
    if not needed:
        raise AssertionError(f"{so_path}: No DT_NEEDED entries found")
    disallowed = [lib for lib in needed if lib not in ALLOWED_NEEDED]
    if disallowed:
        raise AssertionError(f"{so_path}: Disallowed DT_NEEDED dependencies: {disallowed}")

    ph_out = run_readelf(readelf, ["-lW"], so_path)
    load_lines = [line.strip() for line in ph_out.splitlines() if line.lstrip().startswith("LOAD")]
    if not load_lines:
        raise AssertionError(f"{so_path}: No PT_LOAD segments found")
    for line in load_lines:
        align_str = line.split()[-1]
        align = int(align_str, 16)
        if align < 0x4000:
            raise AssertionError(f"{so_path}: PT_LOAD alignment {hex(align)} < 0x4000 ({line})")

    sym_out = run_readelf(readelf, ["--dyn-syms", "-W"], so_path)
    exported: Set[str] = set()
    for line in sym_out.splitlines():
        parts = line.split()
        # Format: Num Value Size Type Bind Vis Ndx Name[@ver]
        if len(parts) < 8:
            continue
        sym_type, bind, vis, ndx, raw_name = parts[3], parts[4], parts[5], parts[6], parts[7]
        if bind != "GLOBAL" or vis != "DEFAULT" or ndx == "UND":
            continue
        name = raw_name.split("@", 1)[0]
        if name in ALLOWED_CRT_SYMS:
            continue
        if sym_type == "FUNC":
            if not name.startswith("omni_ra_"):
                raise AssertionError(f"{so_path}: Unexpected exported symbol '{name}'")
            exported.add(name)

    missing = sorted(REQUIRED_EXPORTS - exported)
    if missing:
        raise AssertionError(f"{so_path}: Missing required exported symbols: {missing}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify libomnidroid_ra.so ELF invariants")
    parser.add_argument("--readelf", required=True, help="Path to llvm-readelf")
    parser.add_argument("binaries", nargs="+", help="Paths to libomnidroid_ra.so")
    args = parser.parse_args()

    for b in args.binaries:
        path = pathlib.Path(b)
        verify_so(args.readelf, path)
        print(f"VERIFIED ELF: {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
