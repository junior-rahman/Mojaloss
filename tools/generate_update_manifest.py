#!/usr/bin/env python3
"""Create or validate MojaLoss update metadata after independent APK verification.

This program does not sign an APK, run apksigner, contact GitHub, or publish files.
The supplied identity fields must come from the verified, final APK. Its bytes are
hashed here and must match the independently recorded --expected-sha256 value.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile


APPLICATION_ID = "com.mojaloss.android.debug"
CERTIFICATE_SHA256 = "dd4c36aace6f8c030ea40a5783015040987c94e2353b7ea48ea8522d6152d9d1"
MAX_APK_BYTES = 200 * 1024 * 1024
MAX_MANIFEST_BYTES = 64 * 1024
MAX_NOTES_CHARACTERS = 20_000
MAX_VERSION_CODE = 2_147_483_647
FIELDS = {
    "versionCode", "versionName", "minSdk", "applicationId", "assetName",
    "size", "sha256", "notes",
}
HEX_DIGEST = re.compile(r"[a-fA-F0-9]{64}")
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")


class InvalidRelease(ValueError):
    """An input does not match the app's release admission rules."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise InvalidRelease(message)


def sha256_file(path: Path) -> tuple[int, str]:
    require(path.is_file(), f"APK is not a regular file: {path}")
    size = path.stat().st_size
    require(1 <= size <= MAX_APK_BYTES, "APK must be between 1 byte and 200 MiB.")
    digest = hashlib.sha256()
    read_bytes = 0
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            read_bytes += len(chunk)
            require(read_bytes <= MAX_APK_BYTES, "APK grew beyond the maximum size while reading.")
            digest.update(chunk)
    require(read_bytes == size, "APK changed size while reading. Freeze the release file first.")
    return size, digest.hexdigest()


def verified_identity(args: argparse.Namespace) -> tuple[int, str]:
    require(args.application_id == APPLICATION_ID, "Application ID does not match the installed app.")
    require(0 <= args.installed_version_code < args.version_code <= MAX_VERSION_CODE,
            "Release versionCode must exceed the installed versionCode and fit an Android integer.")
    require(VERSION.fullmatch(args.version_name) is not None and len(args.version_name) <= 80,
            "Use a stable numeric versionName, for example 0.1.7.")
    require(26 <= args.min_sdk <= MAX_VERSION_CODE, "minSdk must be at least 26.")
    require(args.verified_certificate_sha256.lower() == CERTIFICATE_SHA256,
            "Certificate does not match MojaLoss's pinned signing certificate. Do not use a new key.")
    require(HEX_DIGEST.fullmatch(args.expected_sha256) is not None,
            "Expected APK SHA-256 must contain exactly 64 hexadecimal characters.")
    expected_name = f"MojaLoss-Sage-{args.version_name}-debug.apk"
    require(args.apk.name == expected_name, f"Release APK filename must be {expected_name}.")
    size, digest = sha256_file(args.apk)
    require(digest == args.expected_sha256.lower(),
            "APK SHA-256 differs from the independently verified release file.")
    return size, digest


def validate_fields(fields: object, args: argparse.Namespace, size: int, digest: str) -> dict:
    require(type(fields) is dict and set(fields) == FIELDS, "Manifest must contain exactly the eight documented fields.")
    expected = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "minSdk": args.min_sdk,
        "applicationId": APPLICATION_ID,
        "assetName": args.apk.name,
        "size": size,
        "sha256": digest,
    }
    for key, value in expected.items():
        require(type(fields[key]) is type(value) and fields[key] == value,
                f"Manifest {key} does not match the verified APK metadata.")
    require(type(fields["notes"]) is str and len(fields["notes"]) <= MAX_NOTES_CHARACTERS,
            "Release notes must be text of at most 20,000 characters.")
    return fields


def encode_manifest(fields: dict) -> bytes:
    encoded = (json.dumps(fields, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
    require(len(encoded) <= MAX_MANIFEST_BYTES, "Manifest exceeds the updater's 64 KiB limit.")
    return encoded


def no_duplicate_keys(pairs: list[tuple[str, object]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        require(key not in result, f"Manifest contains duplicate field: {key}")
        result[key] = value
    return result


def atomic_write(path: Path, contents: bytes) -> None:
    require(path.name == "update.json", "Manifest output filename must be update.json.")
    require(path.parent.is_dir(), "Manifest output directory must already exist.")
    temporary: str | None = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".update-", suffix=".tmp", delete=False) as output:
            temporary = output.name
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    for name in ("generate", "validate"):
        command = commands.add_parser(name)
        command.add_argument("--apk", type=Path, required=True)
        command.add_argument("--version-code", type=int, required=True)
        command.add_argument("--version-name", required=True)
        command.add_argument("--min-sdk", type=int, required=True)
        command.add_argument("--application-id", required=True)
        command.add_argument("--installed-version-code", type=int, required=True)
        command.add_argument("--verified-certificate-sha256", required=True,
                             help="Signer digest from independent apksigner verification; this script cannot establish it.")
        command.add_argument("--expected-sha256", required=True,
                             help="APK digest independently recorded after signature and package verification.")
        if name == "generate":
            command.add_argument("--notes-file", type=Path, required=True)
            command.add_argument("--output", type=Path, required=True)
        else:
            command.add_argument("--manifest", type=Path, required=True)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        size, digest = verified_identity(args)
        if args.command == "generate":
            require(args.notes_file.stat().st_size <= MAX_MANIFEST_BYTES, "Release notes file is too large.")
            notes = args.notes_file.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n").strip()
            fields = {
                "versionCode": args.version_code,
                "versionName": args.version_name,
                "minSdk": args.min_sdk,
                "applicationId": args.application_id,
                "assetName": args.apk.name,
                "size": size,
                "sha256": digest,
                "notes": notes,
            }
            validate_fields(fields, args, size, digest)
            atomic_write(args.output, encode_manifest(fields))
            print(f"Created {args.output}; APK {size} bytes; sha256={digest}")
        else:
            require(args.manifest.name == "update.json", "Manifest filename must be update.json.")
            require(args.manifest.stat().st_size <= MAX_MANIFEST_BYTES, "Manifest exceeds the updater's 64 KiB limit.")
            fields = json.loads(args.manifest.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys)
            validate_fields(fields, args, size, digest)
            encode_manifest(fields)
            print(f"Validated {args.manifest}; APK {size} bytes; sha256={digest}")
        return 0
    except (InvalidRelease, OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"Release metadata rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
