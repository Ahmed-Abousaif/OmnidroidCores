# Releasing `omni_ra` & Signing Key Rotation

## 1. Initial Key Generation (One-Time Setup)
Generate a raw 32-byte Ed25519 keypair:
```sh
python3 modules/omni_ra/scripts/ed25519_tool.py keygen
```
1. Store `OMNI_RA_ED25519_PRIVATE_KEY=<base64>` as a protected GitHub Actions repository secret in `OmnidroidCores`.
2. Pin `OMNI_RA_ED25519_PUBLIC_KEY=<base64>` in the Omnidroid app (`RaSignatureKeys.PINNED_PUBLIC_KEYS`). Never commit any private key to git.

## 2. Cutting a Module Release (`ra-vX.Y.Z`)
1. Bump `OMNI_RA_ABI_VERSION_MAJOR` / `MINOR` in `include/omni_ra.h` if ABI changed (`docs/abi.md`).
2. Run host C tests and tooling tests locally:
   ```sh
   cmake -S modules/omni_ra -B build/host-ra -DOMNI_RA_BUILD_TESTS=ON
   cmake --build build/host-ra && ctest --test-dir build/host-ra --output-on-failure
   python3 -m unittest discover -s modules/omni_ra/tests -p "test_*.py" -v
   ```
3. Create and push tag `ra-vX.Y.Z`:
   ```sh
   git tag -a ra-v1.0.0 -m "omni_ra 1.0.0 (rcheevos 12.5.0)"
   git push origin ra-v1.0.0
   ```
4. CI workflow `.github/workflows/ra-module-release.yml` automatically:
   - Runs host C & Python tests.
   - Builds `libomnidroid_ra.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` with NDK r29 and Android API 24.
   - Enforces ELF hygiene via `scripts/verify_elf.py` (`libc`/`libm`/`libdl` only, 16 KB `PT_LOAD`, only `omni_ra_*` exported).
   - Archives unstripped debug symbols separately (`libomnidroid_ra-symbols-<ver>.tar.gz`) and strips binaries.
   - Computes SHA-256 and Ed25519 signatures for every `.so`, updates `manifest.json` + detached `manifest.json.sig`, and publishes to both `ra-vX.Y.Z` and `ra-manifest` GitHub Releases.

## 3. Signing Key Rotation
1. **App release first**: Ship an app update adding the new Ed25519 public key (`keyId` / Base64 32-byte key) to `RaSignatureKeys.PINNED_PUBLIC_KEYS` while keeping the old public key trusted during the transition window.
2. **Switch CI secret**: Replace `OMNI_RA_ED25519_PRIVATE_KEY` in `OmnidroidCores` secrets and tag a new `ra-vX.Y.Z` release (`minAppVersionCode` set to the app version containing the new public key). Older apps reject the new release safely cleanly or select an earlier compatible release signed with the old key.
3. **Retire old key**: After old releases age out, remove the retired public key from a subsequent app update.
