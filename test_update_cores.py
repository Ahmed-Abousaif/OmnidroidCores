import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from update_cores import (
    ALL_ABIS, CORES, CoreSpec, SourceType,
    ensure_dummy_fallback_abis, write_scaffolding, sync_cemu_core
)


class TestUpdateCores(unittest.TestCase):
    def test_registry_contains_all_25_cores(self):
        self.assertEqual(len(CORES), 25)
        for name in [
            "stella", "fceumm", "snes9x", "gambatte", "mgba", "mupen64plus_next_gles3",
            "dolphin", "melondsds", "citra", "azahar", "cemu", "switch", "genesis_plus_gx",
            "flycast", "pcsx_rearmed", "ppsspp", "armsx2", "fbneo", "mame2003_plus",
            "mednafen_pce_fast", "mednafen_ngp", "mednafen_wswan", "dosbox_pure",
            "handy", "prosystem",
        ]:
            self.assertIn(name, CORES)

    def test_core_spec_defaults_and_overrides(self):
        snes = CORES["snes9x"]
        self.assertEqual(snes.target_so_name, "libsnes9x_libretro_android.so")
        self.assertEqual(snes.namespace, "com.omnidroid.core.snes9x")
        self.assertEqual(snes.dummy_abis, ())

        switch = CORES["switch"]
        self.assertEqual(switch.target_so_name, "libyuzu-android.so")
        self.assertEqual(switch.namespace, "com.omnidroid.core.switch_emu")
        self.assertEqual(switch.dummy_abis, ("armeabi-v7a", "x86", "x86_64"))

        cemu = CORES["cemu"]
        self.assertEqual(cemu.target_so_name, "libcemu_libretro_android.so")
        self.assertEqual(cemu.dummy_abis, ("armeabi-v7a", "x86", "x86_64"))

        melondsds = CORES["melondsds"]
        self.assertEqual(melondsds.dummy_abis, ("x86",))

    def test_scaffolding_generation(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            core_dir = pathlib.Path(tmp_dir) / "omnidroid_core_switch"
            write_scaffolding(CORES["switch"], core_dir)

            manifest = core_dir / "src" / "main" / "AndroidManifest.xml"
            gradle = core_dir / "build.gradle.kts"

            self.assertTrue(manifest.exists())
            self.assertTrue(gradle.exists())

            manifest_text = manifest.read_text(encoding="utf-8")
            self.assertIn("@string/core_name_switch", manifest_text)
            self.assertIn("dist:on-demand", manifest_text)

            gradle_text = gradle.read_text(encoding="utf-8")
            self.assertIn('namespace = "com.omnidroid.core.switch_emu"', gradle_text)
            self.assertIn('keepDebugSymbols += setOf("*/*/libyuzu-android.so")', gradle_text)

    def test_dummy_abi_generator(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            jni_dir = pathlib.Path(tmp_dir) / "jniLibs"
            # Create a real arm64 lib
            arm64_dir = jni_dir / "arm64-v8a"
            arm64_dir.mkdir(parents=True)
            (arm64_dir / "libcemu_libretro_android.so").write_bytes(b"REAL_BINARY")

            created = ensure_dummy_fallback_abis(CORES["cemu"], jni_dir)
            self.assertEqual(set(created), {"armeabi-v7a", "x86", "x86_64"})

            # Verify arm64-v8a is untouched
            self.assertEqual((arm64_dir / "libcemu_libretro_android.so").read_bytes(), b"REAL_BINARY")

            # Verify dummy files are 0 bytes
            for abi in ["armeabi-v7a", "x86", "x86_64"]:
                dummy = jni_dir / abi / "libcemu_libretro_android.so"
                self.assertTrue(dummy.exists())
                self.assertEqual(dummy.stat().st_size, 0)

    def test_sync_cemu_core_local_candidate(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            cores_root = pathlib.Path(tmp_dir)
            out_dir = cores_root / "out"
            # Create a mock candidate > 1MB
            candidate = cores_root / "libcemu_libretro_android.so"
            candidate.write_bytes(b"\x00" * 1_000_001)

            ok, msg = sync_cemu_core(CORES["cemu"], cores_root, out_dir, cemu_tag="nonexistent-tag")
            self.assertTrue(ok)
            self.assertIn("Copied from local", msg)
            self.assertTrue((out_dir / "libcemu_libretro_android.so").exists())
            self.assertEqual((out_dir / "libcemu_libretro_android.so").stat().st_size, 1_000_001)


if __name__ == "__main__":
    unittest.main()
