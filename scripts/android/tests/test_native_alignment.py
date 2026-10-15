"""nanoMuse: exercise APK/ELF alignment without an SDK or compiled fixture binaries."""

import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-native-alignment.py"
SPEC = importlib.util.spec_from_file_location("native_alignment", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
alignment = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(alignment)


def elf(loads: list[tuple[int, int, int]], bits: int = 64, endian: str = "<") -> bytes:
    header_size, entry_size = (64, 56) if bits == 64 else (52, 32)
    data = bytearray(header_size + entry_size * len(loads))
    data[:7] = b"\x7fELF" + bytes((2 if bits == 64 else 1, 1 if endian == "<" else 2, 1))
    header = "HHIQQQIHHHHHH" if bits == 64 else "HHIIIIIHHHHHH"
    struct.pack_into(
        endian + header,
        data,
        16,
        3,
        183 if bits == 64 else 40,
        1,
        0,
        header_size,
        0,
        0,
        header_size,
        entry_size,
        len(loads),
        0,
        0,
        0,
    )
    for index, (offset, address, page_size) in enumerate(loads):
        fields = (
            (1, 5, offset, address, 0, 0, 1, page_size)
            if bits == 64
            else (1, offset, address, 0, 0, 1, 5, page_size)
        )
        struct.pack_into(
            endian + ("IIQQQQQQ" if bits == 64 else "IIIIIIII"),
            data,
            header_size + index * entry_size,
            *fields,
        )
    return bytes(data)


class NativeAlignmentTest(unittest.TestCase):
    def apk(
        self,
        directory: str,
        payload: bytes,
        *,
        compressed: bool,
        aligned: bool,
        filename: str = "lib/arm64-v8a/libexample.so",
    ) -> Path:
        path = Path(directory) / "app.apk"
        info = zipfile.ZipInfo(filename)
        info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
        if aligned:
            padding = -(30 + len(info.filename.encode())) % 16384
            info.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(info, payload)
        return path

    def test_accepts_16k_64k_and_both_elf_classes_and_byte_orders(self) -> None:
        for bits in (32, 64):
            for endian in ("<", ">"):
                for page_size in (16384, 65536):
                    with self.subTest(bits=bits, endian=endian, page_size=page_size):
                        self.assertEqual(
                            [], alignment.elf_errors(elf([(0, 0, page_size)], bits, endian))
                        )

    def test_rejects_4k_and_checks_every_load_segment(self) -> None:
        errors = alignment.elf_errors(elf([(0, 0, 16384), (4096, 4096, 4096)]))
        self.assertTrue(any("LOAD 1" in error and "alignment" in error for error in errors))
        self.assertTrue(alignment.elf_errors(elf([(0, 0, 4096)], bits=32)))

    def test_rejects_incongruent_load_offsets_and_invalid_alignments(self) -> None:
        self.assertTrue(
            any("congruent" in error for error in alignment.elf_errors(elf([(4096, 0, 16384)])))
        )
        self.assertTrue(alignment.elf_errors(elf([(0, 0, 24576)])))

    def test_rejects_invalid_or_truncated_elf_headers(self) -> None:
        for payload in (b"not ELF", elf([]), elf([(0, 0, 16384)])[:-1]):
            with self.subTest(payload=payload[:8]):
                self.assertTrue(alignment.elf_errors(payload))

    def test_compressed_libraries_need_elf_alignment_but_not_zip_alignment(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = self.apk(directory, elf([(0, 0, 16384)]), compressed=True, aligned=False)
            self.assertEqual((1, [], []), alignment.check_apk(path))
            path = self.apk(directory, elf([(0, 0, 4096)]), compressed=True, aligned=False)
            self.assertTrue(alignment.check_apk(path)[1])

    def test_uncompressed_libraries_require_the_actual_local_data_offset_to_be_16k(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = self.apk(directory, elf([(0, 0, 16384)]), compressed=False, aligned=False)
            self.assertTrue(any("ZIP" in error for error in alignment.check_apk(path)[1]))
            path = self.apk(directory, elf([(0, 0, 16384)]), compressed=False, aligned=True)
            self.assertEqual((1, [], []), alignment.check_apk(path))

    def test_legacy_guest_exception_requires_exact_name_class_and_pinned_bytes(self) -> None:
        name = "lib/arm64-v8a/libproot-loader32.so"
        pinned = (
            SCRIPT.parents[2]
            / "android/src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader32.so"
        ).read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            path = self.apk(directory, pinned, compressed=True, aligned=False, filename=name)
            self.assertTrue(alignment.check_apk(path)[1])
            count, errors, warnings = alignment.check_apk(path, allow_legacy_proot32=True)
            self.assertEqual(1, count)
            self.assertEqual([], errors)
            self.assertEqual(1, len(warnings))
            self.assertIn("NOT 16 KB", warnings[0])
            for payload, filename in (
                (elf([(0, 0, 4096)], bits=32), name),
                (pinned, "lib/arm64-v8a/libother32.so"),
                (pinned[:-1] + bytes((pinned[-1] ^ 1,)), name),
                (elf([(0, 0, 4096)], bits=64), name),
            ):
                with self.subTest(filename=filename, payload=payload[:8]):
                    path = self.apk(
                        directory, payload, compressed=True, aligned=False, filename=filename
                    )
                    self.assertTrue(alignment.check_apk(path, allow_legacy_proot32=True)[1])

    def test_legacy_guest_exception_never_skips_stored_zip_offset_checks(self) -> None:
        name = "lib/arm64-v8a/libproot-loader32.so"
        pinned = (
            SCRIPT.parents[2]
            / "android/src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader32.so"
        ).read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            path = self.apk(directory, pinned, compressed=False, aligned=False, filename=name)
            self.assertTrue(
                any(
                    "ZIP" in error
                    for error in alignment.check_apk(path, allow_legacy_proot32=True)[1]
                )
            )

    def test_archive_without_native_libraries_does_not_pass_this_native_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "empty.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
            self.assertTrue(alignment.check_apk(path)[1])

    def test_proot_asset_requires_elf_alignment_without_a_zip_offset_requirement(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("assets/proot-aarch64", elf([(0, 0, 16384)]))
            self.assertEqual((1, [], []), alignment.check_apk(path))
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("assets/proot-aarch64", elf([(0, 0, 4096)]))
            self.assertTrue(
                any("assets/proot-aarch64" in error for error in alignment.check_apk(path)[1])
            )


if __name__ == "__main__":
    unittest.main()
