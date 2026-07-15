from __future__ import annotations

import base64
import ctypes
import json
import os
from ctypes import wintypes
from pathlib import Path


class TokenVaultError(RuntimeError):
    pass


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _blob_from_bytes(data: bytes) -> tuple[_DataBlob, ctypes.Array]:
    buffer = ctypes.create_string_buffer(data, len(data))
    blob = _DataBlob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    return blob, buffer


def _dpapi_protect(data: bytes) -> bytes:
    if os.name != "nt":
        return base64.b64encode(data)

    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    input_blob, _ = _blob_from_bytes(data)
    output_blob = _DataBlob()
    description = "HF Storage token"
    flags = 0x01  # CRYPTPROTECT_UI_FORBIDDEN
    ok = crypt32.CryptProtectData(
        ctypes.byref(input_blob),
        description,
        None,
        None,
        None,
        flags,
        ctypes.byref(output_blob),
    )
    if not ok:
        raise TokenVaultError(f"Windows DPAPI şifreleme hatası: {ctypes.GetLastError()}")
    try:
        return ctypes.string_at(output_blob.pbData, output_blob.cbData)
    finally:
        kernel32.LocalFree(output_blob.pbData)


def _dpapi_unprotect(data: bytes) -> bytes:
    if os.name != "nt":
        return base64.b64decode(data)

    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    input_blob, _ = _blob_from_bytes(data)
    output_blob = _DataBlob()
    description = wintypes.LPWSTR()
    flags = 0x01
    ok = crypt32.CryptUnprotectData(
        ctypes.byref(input_blob),
        ctypes.byref(description),
        None,
        None,
        None,
        flags,
        ctypes.byref(output_blob),
    )
    if not ok:
        raise TokenVaultError(f"Windows DPAPI çözme hatası: {ctypes.GetLastError()}")
    try:
        return ctypes.string_at(output_blob.pbData, output_blob.cbData)
    finally:
        if description:
            kernel32.LocalFree(description)
        kernel32.LocalFree(output_blob.pbData)


class TokenVault:
    """Stores the token encrypted for the current Windows user with DPAPI."""

    def __init__(self, app_dir: Path | None = None) -> None:
        base = app_dir or Path(os.getenv("LOCALAPPDATA", Path.home())) / "HFStorage"
        self.base_dir = Path(base)
        self.path = self.base_dir / "credentials.bin"

    def save(self, token: str) -> None:
        token = token.strip()
        if not token:
            raise TokenVaultError("Boş token saklanamaz.")
        self.base_dir.mkdir(parents=True, exist_ok=True)
        payload = json.dumps({"version": 1, "token": token}, separators=(",", ":")).encode("utf-8")
        encrypted = _dpapi_protect(payload)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_bytes(encrypted)
        os.replace(tmp, self.path)
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    def load(self) -> str | None:
        if not self.path.exists():
            return None
        try:
            payload = _dpapi_unprotect(self.path.read_bytes())
            data = json.loads(payload.decode("utf-8"))
            token = str(data.get("token", "")).strip()
            return token or None
        except Exception as exc:
            raise TokenVaultError("Kayıtlı token açılamadı. Oturumu sıfırlayın.") from exc

    def clear(self) -> None:
        try:
            if self.path.exists():
                length = self.path.stat().st_size
                with self.path.open("r+b", buffering=0) as handle:
                    handle.write(os.urandom(length))
                    handle.flush()
                    os.fsync(handle.fileno())
                self.path.unlink(missing_ok=True)
        except OSError:
            self.path.unlink(missing_ok=True)
