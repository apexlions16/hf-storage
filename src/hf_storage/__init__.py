from __future__ import annotations

import os

# huggingface_hub yapılandırmasını kütüphane import edilmeden önce uygula.
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
os.environ.setdefault("HF_XET_HIGH_PERFORMANCE", "1")

__version__ = "0.1.1"

from .upload_fix import install_upload_patch

install_upload_patch()
