from __future__ import annotations

from types import SimpleNamespace

from hf_storage.service import HuggingFaceService
from hf_storage.upload_fix import _robust_upload


class FakeApi:
    def __init__(self) -> None:
        self.auth_checks: list[dict] = []
        self.commits: list[dict] = []

    def auth_check(self, **kwargs):
        self.auth_checks.append(kwargs)

    def create_commit(self, **kwargs):
        self.commits.append(kwargs)
        return SimpleNamespace(commit_url=f"https://huggingface.co/commit/{len(self.commits)}")

    def preupload_lfs_files(self, **kwargs):  # pragma: no cover - must never be called
        raise AssertionError("preupload_lfs_files should not be used")


def make_service() -> HuggingFaceService:
    service = object.__new__(HuggingFaceService)
    service.token = "hf_test_token"
    service.api = FakeApi()
    service._account = None
    return service


def test_100_files_are_uploaded_in_one_commit(tmp_path):
    for index in range(100):
        (tmp_path / f"file-{index:03d}.bin").write_bytes(b"test")

    service = make_service()
    urls = _robust_upload(service, "user/repo", "dataset", [tmp_path])

    assert len(urls) == 1
    assert len(service.api.commits) == 1
    assert len(service.api.commits[0]["operations"]) == 100
    assert service.api.auth_checks[0]["write"] is True


def test_101_files_are_split_into_two_commits(tmp_path):
    for index in range(101):
        (tmp_path / f"file-{index:03d}.bin").write_bytes(b"test")

    service = make_service()
    urls = _robust_upload(service, "user/repo", "model", [tmp_path])

    assert len(urls) == 2
    assert [len(call["operations"]) for call in service.api.commits] == [100, 1]
    assert service.api.commits[0]["commit_message"].endswith("(part 1/2)")
    assert service.api.commits[1]["commit_message"].endswith("(part 2/2)")
