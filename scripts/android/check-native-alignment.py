#!/usr/bin/env python3
"""nanoMuse: check shipped ELF load segments and uncompressed APK library offsets."""

from __future__ import annotations

import argparse
import hashlib
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16384
LEGACY_PROOT32 = "lib/arm64-v8a/libproot-loader32.so"
LEGACY_PROOT32_SHA256 = "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34"


def elf_errors(data: bytes) -> list[str]:
    """Check every PT_LOAD, including ELF32 executables packaged with a .so suffix."""
    if len(data) < 16 or data[:4] != b"\x7fELF":
        return ["not an ELF file"]
    if data[4] not in (1, 2) or data[5] not in (1, 2) or data[6] != 1:
        return ["unsupported ELF class, byte order or version"]
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    header_size, segment_size = (64, 56) if is64 else (52, 32)
    if len(data) < header_size:
        return ["truncated ELF header"]
    table = struct.unpack_from(endian + ("Q" if is64 else "I"), data, 32 if is64 else 28)[0]
    entry_size, count = struct.unpack_from(endian + "HH", data, 54 if is64 else 42)
    if entry_size < segment_size or table < header_size or table + entry_size * count > len(data):
        return ["invalid or truncated ELF program-header table"]
    errors: list[str] = []
    loads = 0
    for index in range(count):
        entry = struct.unpack_from(
            endian + ("IIQQQQQQ" if is64 else "IIIIIIII"), data, table + index * entry_size
        )
        if entry[0] != 1:  # PT_LOAD
            continue
        offset, address, alignment = (
            (entry[2], entry[3], entry[7]) if is64 else (entry[1], entry[2], entry[7])
        )
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            errors.append(f"LOAD {loads}: alignment {alignment} is not a power of two >= 16384")
        if (offset - address) % PAGE_SIZE or (alignment and (offset - address) % alignment):
            errors.append(
                f"LOAD {loads}: offset {offset:#x} and address {address:#x} are not congruent"
            )
        loads += 1
    if loads == 0:
        errors.append("ELF has no PT_LOAD segments")
    return errors


def check_apk(
    path: Path, *, allow_legacy_proot32: bool = False
) -> tuple[int, list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    count = 0
    with path.open("rb") as stream, zipfile.ZipFile(stream) as archive:
        for entry in archive.infolist():
            is_library = entry.filename.endswith(".so")
            if entry.is_dir() or (not is_library and entry.filename != "assets/proot-aarch64"):
                continue
            count += 1
            data = archive.read(entry)
            failures = elf_errors(data)
            # The upstream ARM32 guest loader is not a host JNI library. Keep its exact,
            # hardware-tested bytes until Termux publishes a compatible replacement.
            legacy_guest = (
                allow_legacy_proot32
                and entry.filename == LEGACY_PROOT32
                and data[:5] == b"\x7fELF\x01"
                and hashlib.sha256(data).hexdigest() == LEGACY_PROOT32_SHA256
            )
            if failures and legacy_guest:
                warnings.append(
                    f"{entry.filename}: pinned ARM32 PRoot guest loader is NOT 16 KB aligned; "
                    "upstream replacement remains unavailable. " + "; ".join(failures)
                )
            else:
                errors.extend(f"{entry.filename}: {failure}" for failure in failures)
            if is_library and entry.compress_type == zipfile.ZIP_STORED:
                stream.seek(entry.header_offset)
                local = stream.read(30)
                if len(local) != 30 or local[:4] != b"PK\x03\x04":
                    errors.append(f"{entry.filename}: invalid ZIP local header")
                    continue
                name_size, extra_size = struct.unpack_from("<HH", local, 26)
                data_offset = entry.header_offset + 30 + name_size + extra_size
                if data_offset % PAGE_SIZE:
                    errors.append(
                        f"{entry.filename}: uncompressed ZIP offset {data_offset} "
                        "is not 16 KB aligned"
                    )
    if count == 0:
        errors.append("APK contains no native libraries or PRoot executable")
    return count, errors, warnings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--allow-legacy-proot32",
        action="store_true",
        help="report the exact SHA-pinned ARM32 PRoot guest loader as an unresolved warning",
    )
    args = parser.parse_args(argv)
    try:
        count, errors, warnings = check_apk(
            args.apk, allow_legacy_proot32=args.allow_legacy_proot32
        )
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        print(f"ERROR: {args.apk}: {error}", file=sys.stderr)
        return 1
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(
        f"{args.apk}: {count} native ELF files checked, {len(errors)} errors, "
        f"{len(warnings)} unresolved exceptions"
    )
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
