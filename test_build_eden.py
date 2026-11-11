import json
import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from build_eden import LIBRARIES, sdk_tools, stage_libraries, update_source, validate_library
from update_cores import CORES, main, update_core, verify_cores


def arm64_elf():
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    header[16:18] = (3).to_bytes(2, "little")
    header[18:20] = (183).to_bytes(2, "little")
    return bytes(header)


class EdenBuildTests(unittest.TestCase):
    def test_health_check_requires_all_native_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            update_core(CORES["switch"], root, {"arm64-v8a"}, root,
                        "pinned", "unused", False, True, False)
            libraries = root / "omnidroid_core_switch/src/main/jniLibs/arm64-v8a"
            libraries.mkdir(parents=True)
            (libraries / LIBRARIES[0]).write_bytes(arm64_elf())
            output = io.StringIO()
            with patch("update_cores.CORES", {"switch": CORES["switch"]}), contextlib.redirect_stdout(output):
                self.assertFalse(verify_cores(root))
                for name in LIBRARIES[1:]:
                    (libraries / name).write_bytes(arm64_elf())
                self.assertTrue(verify_cores(root))
            self.assertIn("switch                   eden_source", output.getvalue())

    def test_toolchain_follows_source_pin_instead_of_runner_ndk(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gradle = root / "source/src/android/app/build.gradle.kts"
            gradle.parent.mkdir(parents=True)
            gradle.write_text('ndkVersion = "30.0.1"\ncmake { version = "3.31.6" }')
            ndk = root / "sdk/ndk/30.0.1"
            (ndk / "build/cmake").mkdir(parents=True)
            (ndk / "build/cmake/android.toolchain.cmake").touch()
            tools = root / "sdk/cmake/3.31.6/bin"
            tools.mkdir(parents=True)
            suffix = ".exe" if os.name == "nt" else ""
            for name in ("cmake", "ninja"):
                (tools / (name + suffix)).touch()
            with patch.dict(os.environ, {"ANDROID_HOME": str(root / "sdk"), "ANDROID_NDK_HOME": "old-ndk"}):
                self.assertEqual(sdk_tools(root / "source")[0], ndk)

    def test_latest_master_is_selected_instead_of_release_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            upstream, checkout = root / "upstream", root / "checkout"

            def command(*args, cwd=upstream):
                return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()

            upstream.mkdir()
            command("init", "--initial-branch=master")
            (upstream / "CMakeLists.txt").write_text("project(eden)")
            cache = upstream / "src/common/android/id_cache.cpp"
            cache.parent.mkdir(parents=True)
            cache.write_text("// JNI")
            command("add", ".")
            command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "release")
            command("tag", "v0.2.1")
            command("clone", str(upstream), str(checkout), cwd=root)
            (upstream / "fix.txt").write_text("latest fix")
            command("add", ".")
            command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "latest")
            expected = command("rev-parse", "HEAD")
            self.assertEqual(update_source(checkout), expected)
            self.assertEqual(command("rev-parse", "HEAD", cwd=checkout), expected)
            self.assertEqual(command("rev-parse", "--abbrev-ref", "HEAD", cwd=checkout), "HEAD")
            command("remote", "set-url", "origin", str(root / "offline"), cwd=checkout)
            self.assertEqual(update_source(checkout, "pinned"), expected)
            (checkout / "local.txt").write_text("do not overwrite")
            with self.assertRaisesRegex(RuntimeError, "local changes"):
                update_source(checkout)

    def test_packaging_requires_all_libraries_before_replacing_existing_core(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build/lib").mkdir(parents=True)
            output = root / "core/src/main/jniLibs/arm64-v8a"
            output.mkdir(parents=True)
            (output / LIBRARIES[0]).write_bytes(b"old binary")
            (root / "build/lib" / LIBRARIES[0]).write_bytes(arm64_elf())
            with self.assertRaisesRegex(RuntimeError, "libhook_impl.so"):
                stage_libraries(root / "build", root / "source", output, "sha", root / "ndk")
            self.assertEqual((output / LIBRARIES[0]).read_bytes(), b"old binary")

    def test_packaging_records_source_and_binary_hashes_and_copies_sdl_bridge(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            libraries = root / "build/lib"
            libraries.mkdir(parents=True)
            for name in LIBRARIES:
                (libraries / name).write_bytes(arm64_elf())
            sdl = root / "source/externals/generated/sdl/java/org/libsdl/app"
            sdl.mkdir(parents=True)
            (sdl / "SDL.java").write_text("// original SDL source")
            sdl_root = root / "sdl"
            (sdl_root / "include/SDL3").mkdir(parents=True)
            (sdl_root / "include/SDL3/SDL_copying.h").write_text("SDL license")
            (root / "build/CMakeCache.txt").write_text(f"CPM_PACKAGE_SDL3_SOURCE_DIR:INTERNAL={sdl_root}\n")
            tool = root / "ndk/toolchains/llvm/prebuilt/host/bin/llvm-strip"
            tool.parent.mkdir(parents=True)
            tool.touch()
            output = root / "core/src/main/jniLibs/arm64-v8a"
            with patch("build_eden.run"):
                stage_libraries(root / "build", root / "source", output, "commit-sha", root / "ndk",
                                base_hooks=root / "base-hooks")
            metadata = json.loads((output.parents[1] / "eden-build.json").read_text())
            self.assertEqual(metadata["commit"], "commit-sha")
            self.assertEqual(set(metadata["sha256"]), set(LIBRARIES))
            self.assertTrue((output.parents[1] / "sdl/java/org/libsdl/app/SDL.java").is_file())
            self.assertEqual((output.parents[1] / "sdl/LICENSE.txt").read_text(), "SDL license")
            for name in LIBRARIES[1:]:
                self.assertEqual((root / "base-hooks" / name).read_bytes(), (output / name).read_bytes())

    def test_rejects_wrong_architecture_or_non_elf(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "library.so"
            for data in (b"", b"not an ELF", arm64_elf()[:18] + b"\x3e\x00"):
                binary.write_bytes(data)
                with self.assertRaises(RuntimeError):
                    validate_library(binary)

    def test_native_build_failure_causes_updater_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch("update_cores.build_eden", side_effect=RuntimeError("compile failed")):
                results = update_core(CORES["switch"], Path(directory), {"arm64-v8a"},
                                      Path(directory), "master", "unused", False, False, False)
            self.assertIn("FAIL", results["arm64-v8a"])
            with patch("sys.argv", ["update_cores.py", "--cores", "switch"]), \
                    patch("update_cores.update_core", return_value=results):
                self.assertEqual(main(), 1)

    def test_other_abis_do_not_build_eden(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch("update_cores.build_eden") as build:
                update_core(CORES["switch"], Path(directory), {"x86"}, Path(directory),
                            "master", "unused", False, False, False)
                build.assert_not_called()


if __name__ == "__main__":
    unittest.main()
