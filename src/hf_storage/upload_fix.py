from __future__ import annotations

import os
import traceback
from collections.abc import Iterable
from datetime import datetime
from pathlib import Path
from typing import Any

from huggingface_hub import CommitOperationAdd

from .models import normalize_repo_path
from .service import HFStorageError, HuggingFaceService, ProgressCallback


def _http_status(exc: BaseException) -> int | None:
    response = getattr(exc, "response", None)
    value = getattr(response, "status_code", None)
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _friendly_upload_error(exc: BaseException, repo_id: str, repo_type: str) -> str:
    status = _http_status(exc)
    detail = str(exc).strip() or exc.__class__.__name__
    lowered = detail.lower()

    if status == 401:
        return "Token geçersiz veya süresi dolmuş. Yeni bir read + write token ile tekrar giriş yapın."
    if status == 403:
        return (
            f"{repo_id} deposuna yazma izni yok. Tokenın write yetkisini ve deponun size ait olduğunu kontrol edin."
        )
    if status == 404:
        return (
            f"{repo_id} bulunamadı veya depo türü yanlış algılandı ({repo_type}). "
            "Depoyu yenileyip tekrar seçin."
        )
    if status == 413:
        return "Commit içeriği sunucu sınırını aştı. Daha az dosya seçerek yeniden deneyin."
    if status == 429:
        return "Hugging Face geçici işlem sınırına ulaşıldığını bildirdi. Birkaç dakika sonra tekrar deneyin."
    if status is not None and status >= 500:
        return f"Hugging Face sunucusu geçici bir hata döndürdü ({status}). İşlemi biraz sonra tekrar deneyin."
    if "cas service error" in lowered or "hf_xet" in lowered or "xet" in lowered:
        return (
            "Xet aktarım bileşeni dosyayı gönderemedi. Bu sürümde Xet'in Windows ikili dosyaları pakete "
            "eksiksiz dahil edildi; hata sürerse aşağıdaki tanı günlüğünü paylaşın."
        )
    if "timed out" in lowered or "timeout" in lowered:
        return "Yükleme zaman aşımına uğradı. İnternet bağlantısını kontrol edip yeniden deneyin."
    if "certificate" in lowered or "ssl" in lowered:
        return "Güvenli bağlantı kurulamadı. Windows tarih/saatini ve antivirüsün HTTPS denetimini kontrol edin."

    return f"Yükleme başarısız: {detail}"


def _write_upload_log(service: HuggingFaceService, repo_id: str, repo_type: str) -> Path | None:
    try:
        local_app_data = os.getenv("LOCALAPPDATA")
        root = Path(local_app_data) / "HFStorage" if local_app_data else Path.home() / ".hf-storage"
        log_dir = root / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        log_path = log_dir / "upload-errors.log"
        trace = traceback.format_exc()
        if service.token:
            trace = trace.replace(service.token, "<REDACTED_TOKEN>")
        timestamp = datetime.now().astimezone().isoformat(timespec="seconds")
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(f"\n[{timestamp}] repo={repo_id} type={repo_type}\n{trace}\n")
        return log_path
    except Exception:
        return None


def _robust_upload(
    self: HuggingFaceService,
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

    destination = normalize_repo_path(destination)
    commit_message = commit_message.strip() or "Batch upload with HF Storage"
    total_files = len(files)
    commit_urls: list[str] = []
    completed = 0

    try:
        auth_check = getattr(self.api, "auth_check", None)
        if callable(auth_check):
            auth_check(repo_id=repo_id, repo_type=repo_type, token=self.token, write=True)
    except Exception as exc:
        log_path = _write_upload_log(self, repo_id, repo_type)
        message = _friendly_upload_error(exc, repo_id, repo_type)
        if log_path:
            message += f"\n\nTanı günlüğü: {log_path}"
        raise HFStorageError(message) from exc

    chunks = [
        files[index : index + self.MAX_OPERATIONS_PER_COMMIT]
        for index in range(0, total_files, self.MAX_OPERATIONS_PER_COMMIT)
    ]

    for part, file_chunk in enumerate(chunks, start=1):
        suffix = f" (part {part}/{len(chunks)})" if len(chunks) > 1 else ""
        operations = [
            CommitOperationAdd(
                path_in_repo=normalize_repo_path(destination, relative_path),
                path_or_fileobj=str(local_file),
            )
            for local_file, relative_path in file_chunk
        ]
        self._emit(
            progress,
            "uploading",
            completed,
            total_files,
            f"{len(operations)} dosya tek batch commit ile gönderiliyor{suffix}",
        )

        try:
            # create_commit kendi içinde normal dosya, LFS ve Xet yüklemelerini yönetir.
            # Ayrı preupload_lfs_files çağrısı özellikle paketlenmiş Windows
            # uygulamalarında gereksiz ve daha kırılgan bir ikinci aktarım yolu oluşturuyordu.
            info: Any = self.api.create_commit(
                repo_id=repo_id,
                repo_type=repo_type,
                operations=operations,
                commit_message=f"{commit_message}{suffix}",
                token=self.token,
                num_threads=min(8, max(1, len(operations))),
            )
        except Exception as exc:
            log_path = _write_upload_log(self, repo_id, repo_type)
            message = _friendly_upload_error(exc, repo_id, repo_type)
            if log_path:
                message += f"\n\nTanı günlüğü: {log_path}"
            raise HFStorageError(message) from exc

        completed += len(operations)
        commit_urls.append(str(getattr(info, "commit_url", info)))
        self._emit(progress, "completed", completed, total_files, f"{completed}/{total_files} dosya commit edildi")

    return commit_urls


def install_upload_patch() -> None:
    if getattr(HuggingFaceService.upload, "__hf_storage_v011__", False):
        return
    setattr(_robust_upload, "__hf_storage_v011__", True)
    HuggingFaceService.upload = _robust_upload  # type: ignore[method-assign]
