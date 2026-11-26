# `omni_ra` — Separately Shipped RetroAchievements Module

Pure-C shared library (`libomnidroid_ra.so`) embedding RetroAchievements [`rcheevos`](https://github.com/RetroAchievements/rcheevos) (pinned submodule under `third_party/rcheevos`) and exposing a versioned pure-C ABI ([`include/omni_ra.h`](include/omni_ra.h)).

## Constraints
- Pure C99 (`libc`, `libm`, `libdl` only on Android — no `libc++`, `liblog`, `libandroid`, or JNI).
- `-Wl,-z,max-page-size=16384` (Android 15+ 16 KB page alignment).
- All symbols hidden except `omni_ra_*` (`exports.map` + `--exclude-libs,ALL`).
- Hardcore policy lives solely in the Omnidroid frontend, never in `libomnidroid_ra.so`.

## Building Host Tests
```sh
cmake -S modules/omni_ra -B build/host-ra -DOMNI_RA_BUILD_TESTS=ON
cmake --build build/host-ra
ctest --test-dir build/host-ra --output-on-failure
```

## Building Android ABIs
```sh
for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
  cmake -S modules/omni_ra -B build/android-$ABI \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release \
    -DOMNI_RA_VERSION=1.0.0
  cmake --build build/android-$ABI
done
```

## Updating `rcheevos`
1. Bump `third_party/rcheevos` to a **tagged stable release** (never `develop` or floating `master`):
   ```sh
   git -C modules/omni_ra/third_party/rcheevos fetch --tags
   git -C modules/omni_ra/third_party/rcheevos checkout vX.Y.Z
   ```
2. Verify all public structs and function calls in `src/omni_ra.c` against `rc_client.h`, `rc_hash.h`, `rc_libretro.h`, and `rc_consoles.h`.
3. Update `include/omni_ra.h` only if surfacing new additive fields (bump `OMNI_RA_ABI_VERSION_MINOR`) or breaking signatures (bump `OMNI_RA_ABI_VERSION_MAJOR`). Update `docs/abi.md` accordingly.
4. Run host tests (`ctest`) and Android verification checks (`llvm-readelf` dynamic deps, symbol table, 16 KB `PT_LOAD` alignment).

## Cutting a Module Release
Module releases use independent tags prefixed `ra-vX.Y.Z` (e.g., `ra-v1.0.0`):
1. Ensure CI workflow `.github/workflows/ra-module-release.yml` passes all tests and binary hygiene checks.
2. Tag and push `ra-vX.Y.Z` (with GitHub Actions signing key secret `OMNI_RA_ED25519_PRIVATE_KEY` configured).
3. CI builds all 4 ABIs, strips release `.so`s (archiving unstripped symbols separately), computes SHA-256 + Ed25519 detached signatures, updates the root delivery `manifest.json` + `manifest.json.sig`, and publishes the GitHub Release.
