#!/usr/bin/env python3
"""Copy the Android JVM bridge that matches Cargo's rustls verifier version.

rustls-platform-verifier is primarily a Rust crate, but Android certificate
verification calls the platform TrustManager through a small classes.jar shipped
by rustls-platform-verifier-android. This script discovers the exact Cargo
package selected by Cargo.lock and copies its classes.jar into the APK project.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = REPO_ROOT / "android" / "xet-native" / "Cargo.toml"
DESTINATION = REPO_ROOT / "android" / "app" / "libs" / "rustls-platform-verifier.jar"
PACKAGE_NAME = "rustls-platform-verifier-android"
CLASS_PREFIX = "org/rustls/platformverifier/"


def main() -> int:
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

    metadata = json.loads(process.stdout)
    package = next(
        (item for item in metadata.get("packages", []) if item.get("name") == PACKAGE_NAME),
        None,
    )
    if package is None:
        raise SystemExit(f"Cargo package not found: {PACKAGE_NAME}")

    source = Path(package["manifest_path"]).resolve().parent / "classes.jar"
    if not source.is_file():
        raise SystemExit(f"Android verifier classes.jar not found: {source}")

    with zipfile.ZipFile(source) as archive:
        class_names = [name for name in archive.namelist() if name.endswith(".class")]
        if not any(name.startswith(CLASS_PREFIX) for name in class_names):
            raise SystemExit(
                "rustls verifier classes.jar does not contain the expected "
                f"{CLASS_PREFIX} classes"
            )

    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, DESTINATION)
    print(f"Copied {source} -> {DESTINATION} ({DESTINATION.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
