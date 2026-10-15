"""Archive integrity and no-overwrite checks, independent of native executable compilation."""
import hashlib
import importlib.util
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("package", Path(__file__).resolve().parents[1] / "package-relay-portable.py")
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)


class Packaging(unittest.TestCase):
    def test_archives_and_checksums(self):
        for target, filename in PACKAGE.TARGETS.items():
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temp:
                folder = Path(temp)
                binary = folder / filename
                binary.write_bytes(b"synthetic executable fixture")
                result = PACKAGE.package(binary, target, "a" * 40, folder / "out")
                if result.suffix == ".zip":
                    with zipfile.ZipFile(result) as archive:
                        cells = {Path(n).name: archive.read(n) for n in archive.namelist()}
                else:
                    with tarfile.open(result) as archive:
                        cells = {Path(m.name).name: archive.extractfile(m).read() for m in archive.getmembers()}
                        mode = next(m.mode for m in archive.getmembers() if Path(m.name).name == filename)
                        self.assertEqual(mode, 0o755)
                self.assertEqual(set(cells), {filename, "README.txt", "VERSION.txt", "SHA256SUMS"})
                self.assertEqual(cells[filename], binary.read_bytes())
                for line in cells["SHA256SUMS"].decode().splitlines():
                    digest, name = line.split("  ")
                    self.assertEqual(digest, hashlib.sha256(cells[name]).hexdigest())
                external = result.with_name(result.name + ".sha256").read_text().split("  ")[0]
                self.assertEqual(external, hashlib.sha256(result.read_bytes()).hexdigest())
                before = result.read_bytes()
                with self.assertRaises(FileExistsError):
                    PACKAGE.package(binary, target, "a" * 40, folder / "out")
                self.assertEqual(result.read_bytes(), before)

    def test_rejects_bad_provenance_target_and_filename(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            binary = folder / "wrong.exe"
            binary.write_bytes(b"fixture")
            for target, sha in [("unsupported", "a" * 40), ("x86_64-pc-windows-msvc", "../"), ("x86_64-pc-windows-msvc", "a" * 40)]:
                with self.assertRaises(ValueError):
                    PACKAGE.package(binary, target, sha, folder / "out")


if __name__ == "__main__":
    unittest.main()
