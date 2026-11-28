import json
import os
import pathlib
import sys
import unittest

TESTS_DIR = pathlib.Path(__file__).resolve().parent
MODULE_DIR = TESTS_DIR.parent
SCRIPTS_DIR = MODULE_DIR / "scripts"
CORES_ROOT = MODULE_DIR.parents[1]

sys.path.insert(0, str(MODULE_DIR))
sys.path.insert(0, str(SCRIPTS_DIR))
import build_omni_ra  # noqa: E402
import verify_elf  # noqa: E402


class TestOmniRaTooling(unittest.TestCase):
    def test_core_ra_metadata_covers_libretro_systems(self):
        data = json.loads((MODULE_DIR / "core_ra_metadata.json").read_text(encoding="utf-8"))
        cores = data["cores"]
        self.assertGreaterEqual(len(cores), 27)
        self.assertTrue(cores["snes9x"]["ra_supported"])
        self.assertEqual(cores["snes9x"]["ra_console_ids"], [3])
        self.assertFalse(cores["switch"]["ra_supported"])
        self.assertFalse(cores["armsx3"]["ra_supported"])
        self.assertFalse(cores["mame2003_plus"]["ra_supported"])

    def test_verify_staged_android_jni_libs(self):
        try:
            sdk = build_omni_ra.find_sdk_root()
            ndk = build_omni_ra.find_ndk(sdk)
            readelf = str(build_omni_ra.find_llvm_tool(ndk, "llvm-readelf"))
        except RuntimeError:
            self.skipTest("Android NDK llvm-readelf not found locally")
        sos = [
            MODULE_DIR / "src" / "main" / "jniLibs" / abi / "libomnidroid_ra.so"
            for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        ]
        if not all(p.is_file() for p in sos):
            self.skipTest("Staged libomnidroid_ra.so binaries not present under src/main/jniLibs")
        for so in sos:
            verify_elf.verify_so(readelf, so)


if __name__ == "__main__":
    unittest.main()
