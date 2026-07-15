# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path

from PyInstaller.utils.hooks import collect_all

root = Path(SPECPATH)
icon = root / "assets" / "hf-storage.ico"

hf_xet_datas, hf_xet_binaries, hf_xet_hiddenimports = collect_all("hf_xet")

a = Analysis(
    [str(root / "run.py")],
    pathex=[str(root / "src")],
    binaries=hf_xet_binaries,
    datas=hf_xet_datas,
    hiddenimports=[
        "huggingface_hub",
        "hf_xet",
        *hf_xet_hiddenimports,
        "PySide6.QtSvg",
        "PySide6.QtNetwork",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib", "numpy"],
    noarchive=False,
    optimize=1,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="HFStorage",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(icon) if icon.exists() else None,
)
