from __future__ import annotations

import os
import shutil
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any

from huggingface_hub import (
    CommitOperationAdd,
    CommitOperationCopy,
    CommitOperationDelete,
    HfApi,
    hf_hub_download,
)

from .models import AccountInfo, FileRecord, RepositoryRecord, StorageSnapshot, normalize_repo_path

ProgressCallback = Callable[[str, int, int, str], None]


class HFStorageError(RuntimeError):
    pass


class HuggingFaceService:
    MAX_OPERATIONS_PER_COMMIT = 100

    def __init__(self, token: str, high_performance_xet: bool = True) -> None:
        self.token = token.strip()
        if not self.token:
            raise ValueError("Token gereklidir.")
        if high_performance_xet:
            os.environ.setdefault("HF_XET_HIGH_PERFORMANCE", "1")
        self.api = HfApi(token=self.token, library_name="hf-storage", library_version="0.1.0")
        self._account: AccountInfo | None = None

    @staticmethod
    def _emit(callback: ProgressCallback | None, stage: str, current: int, total: int, message: str) -> None:
        if callback:
            callback(stage, current, total, message)

    def authenticate(self) -> AccountInfo:
        try:
            raw = self.api.whoami(token=self.token, cache=True)
        except Exception as exc:
            raise HFStorageError(f"Hugging Face girişi başarısız: {exc}") from exc

        auth = raw.get("auth") or {}
        access_token = auth.get("accessToken") or auth.get("access_token") or {}
        role = str(access_token.get("role") or access_token.get("type") or raw.get("role") or "unknown")
        orgs = raw.get("orgs") or []
        organization_names = [str(org.get("name") or org.get("id") or "") for org in orgs if isinstance(org, dict)]
        self._account = AccountInfo(
            username=str(raw.get("name") or raw.get("user") or ""),
            fullname=str(raw.get("fullname") or ""),
            avatar_url=str(raw.get("avatarUrl") or raw.get("avatar_url") or ""),
            account_type=str(raw.get("type") or "user"),
            is_pro=bool(raw.get("isPro") or raw.get("is_pro")),
            token_role=role.lower(),
            organizations=[name for name in organization_names if name],
            raw=raw,
        )
        if not self._account.username:
            raise HFStorageError("Token doğrulandı ancak kullanıcı adı alınamadı.")
        if self._account.token_role in {"read", "read-only", "readonly"}:
            raise HFStorageError("Bu token yalnızca okuma yetkili. Read + write yetkili bir token kullanın.")
        return self._account

    def list_repositories(self, namespace: str | None = None) -> list[RepositoryRecord]:
        try:
            repos = list(self.api.list_user_repos(namespace=namespace, token=self.token))
        except AttributeError as exc:
            raise HFStorageError(
                "Bu özellik için güncel huggingface_hub sürümü gerekiyor (1.23 veya daha yeni)."
            ) from exc
        except Exception as exc:
            raise HFStorageError(f"Depolar alınamadı: {exc}") from exc

        records: list[RepositoryRecord] = []
        for repo in repos:
            records.append(
                RepositoryRecord(
                    repo_id=str(getattr(repo, "id", "")),
                    repo_type=str(getattr(repo, "type", "model")),
                    visibility=str(getattr(repo, "visibility", "private")),
                    storage=int(getattr(repo, "storage", 0) or 0),
                    storage_percent=float(getattr(repo, "storage_percent", 0.0) or 0.0),
                    updated_at=getattr(repo, "updated_at", None),
                )
            )
        records.sort(
            key=lambda item: (
                item.storage,
                item.updated_at.timestamp() if item.updated_at is not None else 0.0,
            ),
            reverse=True,
        )
        return records

    def get_storage_snapshot(
        self,
        fallback_capacity_bytes: int,
        namespace: str | None = None,
    ) -> StorageSnapshot:
        repos = self.list_repositories(namespace=namespace)
        return StorageSnapshot.from_repositories(repos, fallback_capacity_bytes)

    def list_files(self, repo_id: str, repo_type: str) -> list[FileRecord]:
        if repo_type == "bucket":
            return self._list_bucket_files(repo_id)
        try:
            entries = list(
                self.api.list_repo_tree(
                    repo_id=repo_id,
                    repo_type=repo_type,
                    recursive=True,
                    expand=True,
                    token=self.token,
                )
            )
        except Exception as exc:
            raise HFStorageError(f"Dosyalar alınamadı: {exc}") from exc

        records: list[FileRecord] = []
        for item in entries:
            is_folder = not hasattr(item, "size")
            lfs = getattr(item, "lfs", None)
            xet_hash = str(getattr(item, "xet_hash", "") or "")
            backend = "Klasör" if is_folder else "Git"
            lfs_oid = ""
            if xet_hash:
                backend = "Xet"
            elif lfs:
                backend = "LFS"
                lfs_oid = str(getattr(lfs, "sha256", "") or (lfs.get("sha256") if isinstance(lfs, dict) else ""))

            last_commit = getattr(item, "last_commit", None)
            last_date = getattr(last_commit, "date", None) if last_commit else None
            last_title = str(getattr(last_commit, "title", "") or "") if last_commit else ""
            security = getattr(item, "security", None)
            security_status = str(getattr(security, "status", "") or "") if security else ""
            records.append(
                FileRecord(
                    path=str(getattr(item, "path", "")),
                    size=int(getattr(item, "size", 0) or 0),
                    is_folder=is_folder,
                    storage_backend=backend,
                    blob_id=str(getattr(item, "blob_id", "") or ""),
                    xet_hash=xet_hash,
                    lfs_oid=lfs_oid,
                    last_modified=last_date,
                    last_commit_title=last_title,
                    security_status=security_status,
                )
            )
        records.sort(key=lambda item: (not item.is_folder, item.path.lower()))
        return records

    def _list_bucket_files(self, bucket_id: str) -> list[FileRecord]:
        try:
            entries = list(self.api.list_bucket_tree(bucket_id=bucket_id, recursive=True, token=self.token))
        except Exception as exc:
            raise HFStorageError(f"Bucket dosyaları alınamadı: {exc}") from exc
        records: list[FileRecord] = []
        for item in entries:
            is_folder = not hasattr(item, "size")
            records.append(
                FileRecord(
                    path=str(getattr(item, "path", "")),
                    size=int(getattr(item, "size", 0) or 0),
                    is_folder=is_folder,
                    storage_backend="Klasör" if is_folder else "Xet",
                    xet_hash=str(getattr(getattr(item, "xet_file_data", None), "file_hash", "") or ""),
                )
            )
        records.sort(key=lambda item: (not item.is_folder, item.path.lower()))
        return records

    def create_repository(self, name: str, repo_type: str, private: bool) -> str:
        name = name.strip().strip("/")
        if not name:
            raise HFStorageError("Depo adı boş olamaz.")
        try:
            result = self.api.create_repo(
                repo_id=name,
                repo_type=repo_type,
                private=private,
                exist_ok=False,
                token=self.token,
            )
            return str(result.repo_id)
        except Exception as exc:
            raise HFStorageError(f"Depo oluşturulamadı: {exc}") from exc

    def upload(
        self,
        repo_id: str,
        repo_type: str,
        local_paths: Iterable[str | Path],
        destination: str = "",
        commit_message: str = "Upload files with HF Storage",
        progress: ProgressCallback | None = None,
    ) -> list[str]:
        if repo_type == "bucket":
            return self._upload_bucket(repo_id, local_paths, destination, progress)

        files = self._collect_upload_files(local_paths)
        if not files:
            raise HFStorageError("Yüklenecek dosya bulunamadı.")

        operations: list[CommitOperationAdd] = []
        destination = normalize_repo_path(destination)
        for local_file, relative_path in files:
            remote_path = normalize_repo_path(destination, relative_path)
            operations.append(CommitOperationAdd(path_in_repo=remote_path, path_or_fileobj=str(local_file)))

        commit_urls: list[str] = []
        chunks = [
            operations[index : index + self.MAX_OPERATIONS_PER_COMMIT]
            for index in range(0, len(operations), self.MAX_OPERATIONS_PER_COMMIT)
        ]
        total_files = len(operations)
        completed = 0
        for part, chunk in enumerate(chunks, start=1):
            suffix = f" (part {part}/{len(chunks)})" if len(chunks) > 1 else ""
            self._emit(progress, "preparing", completed, total_files, f"{len(chunk)} dosya hazırlanıyor{suffix}")
            try:
                self.api.preupload_lfs_files(
                    repo_id=repo_id,
                    repo_type=repo_type,
                    additions=chunk,
                    token=self.token,
                    num_threads=min(8, max(2, len(chunk))),
                    free_memory=True,
                )
                self._emit(progress, "committing", completed, total_files, f"Tek batch commit oluşturuluyor{suffix}")
                info = self.api.create_commit(
                    repo_id=repo_id,
                    repo_type=repo_type,
                    operations=chunk,
                    commit_message=f"{commit_message}{suffix}",
                    token=self.token,
                )
            except Exception as exc:
                raise HFStorageError(f"Yükleme başarısız: {exc}") from exc
            completed += len(chunk)
            commit_urls.append(str(getattr(info, "commit_url", info)))
            self._emit(progress, "completed", completed, total_files, f"{completed}/{total_files} dosya commit edildi")
        return commit_urls

    def _upload_bucket(
        self,
        bucket_id: str,
        local_paths: Iterable[str | Path],
        destination: str,
        progress: ProgressCallback | None,
    ) -> list[str]:
        files = self._collect_upload_files(local_paths)
        destination = normalize_repo_path(destination)
        add = [(str(local), normalize_repo_path(destination, relative)) for local, relative in files]
        total = len(add)
        try:
            for index in range(0, total, 1000):
                chunk = add[index : index + 1000]
                self._emit(progress, "uploading", index, total, f"Bucket'a {len(chunk)} dosya gönderiliyor")
                self.api.batch_bucket_files(bucket_id=bucket_id, add=chunk, token=self.token)
                self._emit(progress, "completed", min(index + len(chunk), total), total, "Bucket yüklemesi tamamlandı")
        except Exception as exc:
            raise HFStorageError(f"Bucket yüklemesi başarısız: {exc}") from exc
        return [f"https://huggingface.co/buckets/{bucket_id}"]

    def _collect_upload_files(self, local_paths: Iterable[str | Path]) -> list[tuple[Path, str]]:
        collected: list[tuple[Path, str]] = []
        for raw in local_paths:
            path = Path(raw).resolve()
            if path.is_file():
                collected.append((path, path.name))
            elif path.is_dir():
                for file_path in sorted(item for item in path.rglob("*") if item.is_file()):
                    if ".git" in file_path.parts:
                        continue
                    collected.append((file_path, normalize_repo_path(path.name, file_path.relative_to(path).as_posix())))
        return collected

    def delete_paths(
        self,
        repo_id: str,
        repo_type: str,
        paths: Iterable[str],
        permanent_large_file_cleanup: bool = True,
        progress: ProgressCallback | None = None,
    ) -> dict[str, int]:
        normalized = sorted({normalize_repo_path(path) for path in paths if path.strip()})
        if not normalized:
            raise HFStorageError("Silinecek dosya seçilmedi.")

        if repo_type == "bucket":
            try:
                self.api.batch_bucket_files(bucket_id=repo_id, delete=normalized, token=self.token)
            except Exception as exc:
                raise HFStorageError(f"Bucket silme işlemi başarısız: {exc}") from exc
            return {"deleted": len(normalized), "purged": len(normalized)}

        chunks = [
            normalized[index : index + self.MAX_OPERATIONS_PER_COMMIT]
            for index in range(0, len(normalized), self.MAX_OPERATIONS_PER_COMMIT)
        ]
        deleted = 0
        try:
            for part, chunk in enumerate(chunks, start=1):
                operations = [CommitOperationDelete(path_in_repo=path) for path in chunk]
                self._emit(progress, "deleting", deleted, len(normalized), f"Silme commit'i {part}/{len(chunks)}")
                self.api.create_commit(
                    repo_id=repo_id,
                    repo_type=repo_type,
                    operations=operations,
                    commit_message="Delete files with HF Storage",
                    token=self.token,
                )
                deleted += len(chunk)
        except Exception as exc:
            raise HFStorageError(f"Dosyalar depodan kaldırılamadı: {exc}") from exc

        purged = 0
        if permanent_large_file_cleanup:
            self._emit(progress, "purging", deleted, len(normalized), "Büyük dosya nesneleri ve geçmiş referansları temizleniyor")
            try:
                lfs_files = list(self.api.list_lfs_files(repo_id=repo_id, repo_type=repo_type, token=self.token))
                selected = [item for item in lfs_files if str(getattr(item, "filename", "")) in normalized]
                if selected:
                    self.api.permanently_delete_lfs_files(
                        repo_id=repo_id,
                        repo_type=repo_type,
                        lfs_files=selected,
                        rewrite_history=True,
                        token=self.token,
                    )
                    purged = len(selected)
            except Exception as exc:
                raise HFStorageError(
                    "Dosyalar ana daldan silindi ancak kalıcı LFS/Xet temizliği tamamlanamadı: " + str(exc)
                ) from exc
        self._emit(progress, "completed", len(normalized), len(normalized), "Silme işlemi tamamlandı")
        return {"deleted": deleted, "purged": purged}

    def rename_path(self, repo_id: str, repo_type: str, old_path: str, new_path: str) -> str:
        if repo_type == "bucket":
            raise HFStorageError("Bucket yeniden adlandırma henüz desteklenmiyor.")
        old_path = normalize_repo_path(old_path)
        new_path = normalize_repo_path(new_path)
        if not old_path or not new_path or old_path == new_path:
            raise HFStorageError("Geçerli ve farklı bir hedef yol girin.")
        try:
            info = self.api.create_commit(
                repo_id=repo_id,
                repo_type=repo_type,
                operations=[
                    CommitOperationCopy(src_path_in_repo=old_path, path_in_repo=new_path),
                    CommitOperationDelete(path_in_repo=old_path),
                ],
                commit_message=f"Move {old_path} to {new_path}",
                token=self.token,
            )
            return str(getattr(info, "commit_url", info))
        except Exception as exc:
            raise HFStorageError(f"Dosya taşınamadı: {exc}") from exc

    def download_file(self, repo_id: str, repo_type: str, path: str, destination_dir: str | Path) -> Path:
        if repo_type == "bucket":
            raise HFStorageError("Bucket indirme bu sürümde desteklenmiyor.")
        destination_dir = Path(destination_dir)
        destination_dir.mkdir(parents=True, exist_ok=True)
        try:
            cached = Path(
                hf_hub_download(
                    repo_id=repo_id,
                    repo_type=repo_type,
                    filename=path,
                    token=self.token,
                )
            )
            target = destination_dir / Path(path).name
            shutil.copy2(cached, target)
            return target
        except Exception as exc:
            raise HFStorageError(f"Dosya indirilemedi: {exc}") from exc

    @staticmethod
    def repository_url(repo_id: str, repo_type: str) -> str:
        if repo_type == "model":
            return f"https://huggingface.co/{repo_id}"
        if repo_type == "bucket":
            return f"https://huggingface.co/buckets/{repo_id}"
        return f"https://huggingface.co/{repo_type}s/{repo_id}"
