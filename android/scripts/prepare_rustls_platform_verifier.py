#!/usr/bin/env python3
"""Extract the Android JVM bridge matching Cargo's rustls verifier version.

rustls-platform-verifier is primarily a Rust crate, but Android certificate
verification calls the platform TrustManager through a small classes.jar. The
`rustls-platform-verifier-android` Cargo package ships that jar inside a local
Maven AAR. This script discovers Cargo's exact package version, extracts
classes.jar and places it in the Android app's libs directory.
"""

from __future__ import annotations

import json
import subprocess
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = REPO_ROOT / "android" / "xet-native" / "Cargo.toml"
DESTINATION = REPO_ROOT / "android" / "app" / "libs" / "rustls-platform-verifier.jar"
PACKAGE_NAME = "rustls-platform-verifier-android"
CLASS_PREFIX = "org/rustls/platformverifier/"


def cargo_metadata() -> dict:
    command = [
        "cargo",
        "metadata",
        "--format-version",
        "1",
        "--filter-platform",
        "aarch64-linux-android",
        "--manifest-path",
        str(MANIFEST),
    ]
    process = subprocess.run(
        command,
        cwd=REPO_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if process.returncode != 0:
        print(process.stdout, file=sys.stderr)
        print(process.stderr, file=sys.stderr)
        raise SystemExit("cargo metadata failed while locating rustls verifier bridge")
    return json.loads(process.stdout)


def find_package(metadata: dict) -> dict:
    package = next(
        (item for item in metadata.get("packages", []) if item.get("name") == PACKAGE_NAME),
        None,
    )
    if package is None:
        raise SystemExit(f"Cargo package not found: {PACKAGE_NAME}")
    return package


def extract_classes_jar(package: dict) -> None:
    package_root = Path(package["manifest_path"]).resolve().parent
    version = package["version"]

    # Older development layouts exposed classes.jar directly. Published crates
    # ship a Maven repository containing an AAR, so support both layouts.
    direct_jar = package_root / "classes.jar"
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)

    if direct_jar.is_file():
        DESTINATION.write_bytes(direct_jar.read_bytes())
        source_description = str(direct_jar)
    else:
        version_directory = (
            package_root
            / "maven"
            / "rustls"
            / "rustls-platform-verifier"
            / version
        )
        candidates = sorted(version_directory.glob("*.aar"))
        if not candidates:
            candidates = sorted(
                (package_root / "maven").glob(
                    "rustls/rustls-platform-verifier/*/*.aar"
                )
            )
        if not candidates:
            raise SystemExit(
                "Android verifier AAR not found under Cargo package: "
                f"{package_root}"
            )

        aar = candidates[-1]
        with zipfile.ZipFile(aar) as archive:
            try:
                classes_bytes = archive.read("classes.jar")
            except KeyError as error:
                raise SystemExit(f"AAR does not contain classes.jar: {aar}") from error
        DESTINATION.write_bytes(classes_bytes)
        source_description = f"{aar}!/classes.jar"

    with zipfile.ZipFile(DESTINATION) as archive:
        class_names = [name for name in archive.namelist() if name.endswith(".class")]
        if not any(name.startswith(CLASS_PREFIX) for name in class_names):
            DESTINATION.unlink(missing_ok=True)
            raise SystemExit(
                "rustls verifier classes.jar does not contain the expected "
                f"{CLASS_PREFIX} classes"
            )

    print(
        f"Extracted {source_description} -> {DESTINATION} "
        f"({DESTINATION.stat().st_size} bytes)"
    )


def main() -> int:
    extract_classes_jar(find_package(cargo_metadata()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
