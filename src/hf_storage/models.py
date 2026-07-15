from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from pathlib import PurePosixPath
from typing import Any


DECIMAL_UNITS = ("B", "KB", "MB", "GB", "TB", "PB")


def format_bytes(value: int | float | None, precision: int = 2) -> str:
    """Format bytes using decimal units, matching Hugging Face's storage UI."""
    size = float(value or 0)
    unit = 0
    while size >= 1000 and unit < len(DECIMAL_UNITS) - 1:
        size /= 1000
        unit += 1
    if unit == 0:
        return f"{int(size)} {DECIMAL_UNITS[unit]}"
    text = f"{size:.{precision}f}".rstrip("0").rstrip(".")
    return f"{text} {DECIMAL_UNITS[unit]}"


def format_datetime(value: datetime | None) -> str:
    if not value:
        return "—"
    try:
        return value.astimezone().strftime("%d.%m.%Y %H:%M")
    except Exception:
        return value.strftime("%d.%m.%Y %H:%M")


def normalize_repo_path(*parts: str) -> str:
    cleaned = [part.replace("\\", "/").strip("/") for part in parts if part and part.strip("/")]
    if not cleaned:
        return ""
    path = PurePosixPath(cleaned[0])
    for part in cleaned[1:]:
        path /= part
    normalized = str(path)
    if normalized.startswith("../") or normalized == "..":
        raise ValueError("Depo yolu üst klasöre çıkamaz.")
    return normalized


@dataclass(slots=True)
class AccountInfo:
    username: str
    fullname: str = ""
    avatar_url: str = ""
    account_type: str = "user"
    is_pro: bool = False
    token_role: str = "unknown"
    organizations: list[str] = field(default_factory=list)
    raw: dict[str, Any] = field(default_factory=dict, repr=False)


@dataclass(slots=True)
class RepositoryRecord:
    repo_id: str
    repo_type: str
    visibility: str
    storage: int
    storage_percent: float = 0.0
    updated_at: datetime | None = None

    @property
    def is_private(self) -> bool:
        return self.visibility.lower() == "private"

    @property
    def display_type(self) -> str:
        return {
            "model": "Model",
            "dataset": "Dataset",
            "space": "Space",
            "bucket": "Bucket",
        }.get(self.repo_type, self.repo_type.title())


@dataclass(slots=True)
class FileRecord:
    path: str
    size: int = 0
    is_folder: bool = False
    storage_backend: str = "Git"
    blob_id: str = ""
    xet_hash: str = ""
    lfs_oid: str = ""
    last_modified: datetime | None = None
    last_commit_title: str = ""
    security_status: str = ""

    @property
    def name(self) -> str:
        return self.path.rstrip("/").split("/")[-1]

    @property
    def extension(self) -> str:
        if self.is_folder or "." not in self.name:
            return ""
        return self.name.rsplit(".", 1)[-1].lower()


@dataclass(slots=True)
class StorageSnapshot:
    repositories: list[RepositoryRecord]
    used_bytes: int
    estimated_capacity_bytes: int
    used_percent: float
    capacity_source: str
    fetched_at: datetime

    @property
    def remaining_bytes(self) -> int:
        return max(self.estimated_capacity_bytes - self.used_bytes, 0)

    @classmethod
    def from_repositories(
        cls,
        repositories: list[RepositoryRecord],
        fallback_capacity_bytes: int,
    ) -> "StorageSnapshot":
        used = sum(max(repo.storage, 0) for repo in repositories)
        summed_percent = sum(max(repo.storage_percent, 0.0) for repo in repositories)

        capacity = max(int(fallback_capacity_bytes), 1)
        source = "local-fallback"
        if used > 0 and 0.0001 < summed_percent <= 100.5:
            inferred = int(used / (summed_percent / 100.0))
            if inferred >= used:
                capacity = inferred
                source = "huggingface-percentage"

        percent = min((used / capacity) * 100.0, 100.0) if capacity else 0.0
        return cls(
            repositories=repositories,
            used_bytes=used,
            estimated_capacity_bytes=capacity,
            used_percent=percent,
            capacity_source=source,
            fetched_at=datetime.now().astimezone(),
        )
