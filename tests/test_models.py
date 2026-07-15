from hf_storage.models import RepositoryRecord, StorageSnapshot, format_bytes, normalize_repo_path


def test_format_bytes_decimal_units():
    assert format_bytes(0) == "0 B"
    assert format_bytes(1_000_000_000) == "1 GB"
    assert format_bytes(8_700_000_000_000) == "8.7 TB"


def test_normalize_repo_path():
    assert normalize_repo_path("video", "2026\\clip.mp4") == "video/2026/clip.mp4"
    assert normalize_repo_path("") == ""


def test_capacity_is_inferred_from_storage_percent():
    repos = [
        RepositoryRecord("user/a", "dataset", "private", 500_000_000_000, 5.0),
        RepositoryRecord("user/b", "dataset", "private", 370_000_000_000, 3.7),
    ]
    snapshot = StorageSnapshot.from_repositories(repos, fallback_capacity_bytes=100_000_000_000)
    assert snapshot.capacity_source == "huggingface-percentage"
    assert 9_900_000_000_000 <= snapshot.estimated_capacity_bytes <= 10_100_000_000_000
    assert snapshot.remaining_bytes > 9_000_000_000_000
