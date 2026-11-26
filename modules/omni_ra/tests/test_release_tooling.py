import base64
import hashlib
import json
import os
import pathlib
import sys
import tempfile
import unittest

TESTS_DIR = pathlib.Path(__file__).resolve().parent
MODULE_DIR = TESTS_DIR.parent
SCRIPTS_DIR = MODULE_DIR / "scripts"
CORES_ROOT = MODULE_DIR.parents[1]

sys.path.insert(0, str(SCRIPTS_DIR))
import ed25519_tool  # noqa: E402
import update_manifest  # noqa: E402
import verify_elf  # noqa: E402


class TestReleaseTooling(unittest.TestCase):
    def test_ed25519_rfc8032_vector1_and_tamper(self):
        # Official RFC 8032 Section 7.1 TEST 1 (empty message)
        seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        expected_pub = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        expected_sig = bytes.fromhex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        self.assertEqual(ed25519_tool.public_from_seed(seed), expected_pub)
        self.assertEqual(ed25519_tool.sign_bytes(seed, b""), expected_sig)
        self.assertTrue(ed25519_tool.verify_bytes(expected_pub, expected_sig, b""))
        self.assertFalse(ed25519_tool.verify_bytes(expected_pub, expected_sig, b"x"))
        bad_sig = bytearray(expected_sig)
        bad_sig[0] ^= 1
        self.assertFalse(ed25519_tool.verify_bytes(expected_pub, bytes(bad_sig), b""))

    def test_manifest_generation_history_and_signing(self):
        seed = os.urandom(32)
        pub = ed25519_tool.public_from_seed(seed)

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            art_dir = root / "artifacts"
            for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
                d = art_dir / abi
                d.mkdir(parents=True)
                (d / "libomnidroid_ra.so").write_bytes(f"ELF-{abi}-1.0.0".encode("utf-8"))

            sym_file = root / "libomnidroid_ra-symbols-1.0.0.tar.gz"
            sym_file.write_bytes(b"SYM")

            manifest_path = root / "manifest.json"
            old_rel = update_manifest.build_module_release(
                version="0.9.0",
                abi_major=1,
                abi_minor=0,
                rcheevos_version="12.5.0",
                min_app_version_code=320,
                release_tag="ra-v0.9.0",
                repo_slug="Ahmed-Abousaif/OmnidroidCores",
                artifacts_dir=art_dir,
                symbols_file=None,
                seed=seed,
            )
            rel = update_manifest.build_module_release(
                version="1.0.0",
                abi_major=1,
                abi_minor=0,
                rcheevos_version="12.5.0",
                min_app_version_code=320,
                release_tag="ra-v1.0.0",
                repo_slug="Ahmed-Abousaif/OmnidroidCores",
                artifacts_dir=art_dir,
                symbols_file=sym_file,
                seed=seed,
            )

            obj = {
                "schemaVersion": 1,
                "coresVersion": "3.2.0",
                "modules": {"omni_ra": sorted([old_rel, rel], key=lambda r: update_manifest.semver_key(r["version"]), reverse=True)},
                "cores": update_manifest.build_cores_block(MODULE_DIR / "core_ra_metadata.json"),
            }
            raw_json = (json.dumps(obj, indent=2) + "\n").encode("utf-8")
            manifest_path.write_bytes(raw_json)
            sig = ed25519_tool.sign_bytes(seed, raw_json)

            self.assertTrue(ed25519_tool.verify_bytes(pub, sig, manifest_path.read_bytes()))
            parsed = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual([r["version"] for r in parsed["modules"]["omni_ra"]], ["1.0.0", "0.9.0"])
            self.assertEqual(len(parsed["cores"]), 27)
            self.assertTrue(parsed["cores"]["snes9x"]["ra"]["ra_supported"])
            self.assertEqual(parsed["cores"]["snes9x"]["ra"]["ra_console_ids"], [3])
            self.assertFalse(parsed["cores"]["mame2003_plus"]["ra"]["ra_supported"])

            # Verify artifact signature & SHA-256 match
            arm64 = parsed["modules"]["omni_ra"][0]["artifacts"]["arm64-v8a"]
            bin_bytes = (art_dir / "arm64-v8a" / "libomnidroid_ra.so").read_bytes()
            self.assertEqual(arm64["sha256"], hashlib.sha256(bin_bytes).hexdigest())
            self.assertTrue(ed25519_tool.verify_bytes(pub, base64.b64decode(arm64["sigEd25519"]), bin_bytes))

    def test_verify_built_android_sos_if_present(self):
        ndk_bins = list((pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/ndk").glob("*/toolchains/llvm/prebuilt/*/bin/llvm-readelf*"))
        if not ndk_bins:
            self.skipTest("Android NDK llvm-readelf not found locally")
        readelf = str(sorted(ndk_bins)[-1])
        sos = [CORES_ROOT / "build" / f"android-{abi}" / "libomnidroid_ra.so" for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")]
        if not all(p.is_file() for p in sos):
            self.skipTest("Android build outputs not present under build/android-*")
        for so in sos:
            verify_elf.verify_so(readelf, so)


if __name__ == "__main__":
    unittest.main()
