import io
import json
import pathlib
import unittest
import zipfile
from unittest.mock import patch
import package_firmware


class FirmwarePackageTests(unittest.TestCase):
    def test_checked_in_archives_match_the_pinned_configuration(self):
        config = json.loads(package_firmware.CONFIG_PATH.read_text())
        self.assertEqual(set(config["packages"]), {"saturn", "colecovision", "msx"})
        for name, entries in config["packages"].items():
            package_firmware.validate_package((package_firmware.ROOT / "assets" / (name + ".zip")).read_bytes(), entries)

    def test_malformed_missing_duplicate_and_extra_entries_are_rejected(self):
        spec = [{"name": "bios.bin", "size": 4, "md5": "8d777f385d3dfec8815d20f7496026dc"}]
        with self.assertRaises((ValueError, zipfile.BadZipFile)):
            package_firmware.validate_package(b"not a ZIP", spec)
        for entries in [[], [("bios.bin", b"bad!")], [("bios.bin", b"data"), ("extra.bin", b"extra")]]:
            data = io.BytesIO()
            with zipfile.ZipFile(data, "w") as archive:
                for name, payload in entries:
                    archive.writestr(name, payload)
            with self.assertRaises(ValueError):
                package_firmware.validate_package(data.getvalue(), spec)

    def test_downloaded_content_must_match_before_packaging(self):
        spec = [{"name": "bios.bin", "source": "test", "sourcePath": "bios.bin", "size": 4, "md5": "8d777f385d3dfec8815d20f7496026dc"}]
        source = {"test": {"repository": "https://github.com/test/firmware", "revision": "a" * 40}}
        with patch("package_firmware.urllib.request.urlopen", return_value=io.BytesIO(b"bad!")):
            with self.assertRaises(ValueError):
                package_firmware.build_package(spec, source)
        with patch("package_firmware.urllib.request.urlopen", side_effect=lambda *args, **kwargs: io.BytesIO(b"data")):
            first, _ = package_firmware.build_package(spec, source)
            second, _ = package_firmware.build_package(spec, source)
            self.assertEqual(first, second)
