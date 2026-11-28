# Managed firmware archives

These packages are used by Omnidroid's managed firmware installer, following the Sega CD asset installation lifecycle.

- `saturn.zip`: YabaSanshiro Saturn BIOS.
- `colecovision.zip`: Gearcoleco ColecoVision BIOS.
- `msx.zip`: shared fMSX firmware for MSX/MSX2, disk support and the supported sound/font expansions.

`firmware-sources.json` records the exact upstream source revisions, download URLs, MD5 values, SHA-256 values and sizes. Package entries use the filenames expected by the cores.

The root `firmware-packages.json` is the generation specification. Run `python package_firmware.py` from OmnidroidCores to regenerate the ZIPs, or `python package_firmware.py --verify` to validate checked-in archives without network access. Downloads and archive entries must match the declared checksums. Tests also reject missing, extra, duplicate and malformed entries.

The app build copies only these three ZIPs into `assets/firmware/`. This makes first installation work offline, including before the changed OmnidroidCores archives have been published. Builds without embedded copies use the OmnidroidCores `master/assets/` download URLs. Publish the changed ZIPs there before relying on that remote fallback.

Atari 5200, Atari800 and Opera already provide built-in replacement boot firmware in the selected cores. The configured VICE x64, AppleWin and Virtual Jaguar machines require no external firmware package.
