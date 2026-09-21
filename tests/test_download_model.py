import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZIP_STORED, ZipFile

import download_model


class PackageModelTest(unittest.TestCase):
    def test_packages_model_projector_and_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            destination = Path(temporary_directory)
            model_bytes = b"GGUF-model"
            projector_bytes = b"GGUF-projector"
            (destination / download_model.MODEL_FILE).write_bytes(model_bytes)
            (destination / download_model.PROJECTOR_FILE).write_bytes(projector_bytes)

            package = download_model.package_model(destination)

            self.assertFalse((destination / download_model.MODEL_FILE).exists())
            self.assertFalse((destination / download_model.PROJECTOR_FILE).exists())
            with ZipFile(package) as archive:
                self.assertEqual(
                    archive.namelist(),
                    [
                        download_model.MANIFEST_FILE,
                        download_model.MODEL_FILE,
                        download_model.PROJECTOR_FILE,
                    ],
                )
                self.assertTrue(all(entry.compress_type == ZIP_STORED for entry in archive.infolist()))
                manifest = json.loads(archive.read(download_model.MANIFEST_FILE))
                self.assertEqual(manifest["formatVersion"], 1)
                self.assertEqual(manifest["modelId"], download_model.MODEL_ID)
                self.assertEqual(manifest["model"]["size"], len(model_bytes))
                self.assertEqual(
                    manifest["model"]["sha256"],
                    hashlib.sha256(model_bytes).hexdigest(),
                )
                self.assertEqual(manifest["projector"]["size"], len(projector_bytes))
                self.assertEqual(
                    manifest["projector"]["sha256"],
                    hashlib.sha256(projector_bytes).hexdigest(),
                )


if __name__ == "__main__":
    unittest.main()
