# `omni_ra` — RetroAchievements Native Module

Pure-C shared library (`libomnidroid_ra.so`) embedding RetroAchievements [`rcheevos`](https://github.com/RetroAchievements/rcheevos) (pinned submodule under `third_party/rcheevos`) and exposing a versioned pure-C ABI ([`include/omni_ra.h`](include/omni_ra.h)).

## Constraints
- Pure C99 (`libc`, `libm`, `libdl` only on Android — no `libc++`, `liblog`, `libandroid`, or JNI).
- `-Wl,-z,max-page-size=16384` (Android 15+ 16 KB page alignment).
- All symbols hidden except `omni_ra_*` (`exports.map` + `--exclude-libs,ALL`).
- Hardcore policy lives solely in the Omnidroid frontend, never in `libomnidroid_ra.so`.

## Delivery Layout (Matches Normal Cores)
Prebuilt, stripped binaries for all 4 Android ABIs live directly under:
`modules/omni_ra/src/main/jniLibs/<abi>/libomnidroid_ra.so`

- `free` dynamic builds download `libomnidroid_ra.so` directly from `OmnidroidCores/<VERSION>/modules/omni_ra/src/main/jniLibs/<abi>/libomnidroid_ra.so` just like normal cores.
- `bundle` builds pick up `modules/omni_ra/src/main/jniLibs` via `:bundled-cores`.

## Building & Staging Android ABIs
```sh
python modules/omni_ra/build_omni_ra.py
```

## Building Host Tests
```sh
cmake -S modules/omni_ra -B build/host-ra -DOMNI_RA_BUILD_TESTS=ON
cmake --build build/host-ra
ctest --test-dir build/host-ra --output-on-failure
```

## Updating `rcheevos`
1. Bump `third_party/rcheevos` to a **tagged stable release** (never `develop` or floating `master`):
   ```sh
   git -C modules/omni_ra/third_party/rcheevos fetch --tags
   git -C modules/omni_ra/third_party/rcheevos checkout vX.Y.Z
   ```
2. Verify all public structs and function calls in `src/omni_ra.c` against `rc_client.h`, `rc_hash.h`, `rc_libretro.h`, and `rc_consoles.h`.
3. Update `include/omni_ra.h` only if surfacing new additive fields (bump `OMNI_RA_ABI_VERSION_MINOR`) or breaking signatures (bump `OMNI_RA_ABI_VERSION_MAJOR`). Update `docs/abi.md` accordingly.
4. Run `ctest`, `python -m unittest modules/omni_ra/tests/test_omni_ra_tooling.py`, and `python modules/omni_ra/build_omni_ra.py`.
