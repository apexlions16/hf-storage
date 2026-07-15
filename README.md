# HF Storage

**HF Storage**, Hugging Face model/dataset/Space depolarını Windows üzerinde kişisel bulut disk gibi yönetmek için geliştirilmiş, koyu temalı masaüstü dosya yöneticisidir.

Uygulama herhangi bir aracı sunucu, geliştirici hesabı veya telemetri servisi kullanmaz. Kullanıcının tokenı yalnızca doğrudan `huggingface.co` ile iletişim kurmak için kullanılır ve kullanıcı isterse Windows DPAPI ile mevcut Windows hesabına bağlı biçimde şifrelenerek saklanır.

## Öne çıkan özellikler

- Read + write yetkili Hugging Face User Access Token ile giriş
- Model, Dataset, Space ve Bucket depolarını tek panelde listeleme
- Hugging Face Settings ekranındaki gerçek `storage` ve `storagePercent` verilerini kullanarak canlı kullanım/kalan alan göstergesi
- API kapasite oranı döndürmezse kullanıcı tarafından ayarlanabilen yedek kapasite
- Her 60 saniyede otomatik yenileme ve yükleme/silme sonrası anında güncelleme
- Dosya ağacı, arama, tür simgeleri, Git/LFS/Xet arka uç bilgisi, son commit ve boyut sütunları
- Dosya ve klasör yükleme
- **100 dosyaya kadar tek batch commit**: `CommitOperationAdd` + `preupload_lfs_files()` + `create_commit()`
- 100'den fazla dosyada sunucu sınırlarına uygun otomatik commit parçalama
- Xet yüksek performans modu (`HF_XET_HIGH_PERFORMANCE=1`)
- Dosya indirme, taşıma/yeniden adlandırma ve Hugging Face web sayfasında açma
- İki aşamalı kalıcı silme:
  1. Dosyaları ana daldan commit ile kaldırma
  2. `list_lfs_files()` + `permanently_delete_lfs_files(rewrite_history=True)` ile Storage Usage alanındaki büyük dosya nesnelerini ve geçmiş referanslarını temizleme
- Kalıcı silme öncesinde geri alınamazlık uyarısı ve `SİL` doğrulaması
- GitHub Actions üzerinden otomatik Windows `.exe` derleme

## Güvenlik modeli

- Token hiçbir zaman kaynak koda, loglara veya GitHub Actions'a yazılmaz.
- Uygulama telemetri göndermez.
- “Beni hatırla” seçilirse token `%LOCALAPPDATA%\HFStorage\credentials.bin` içinde Windows DPAPI ile şifrelenir.
- Açıkça read-only olduğu tespit edilen tokenlar reddedilir.
- Çıkış yapıldığında yerel token dosyası üzerine rastgele veri yazılarak silinir.
- Dosya işlemleri doğrudan Hugging Face Hub Python istemcisi üzerinden gerçekleştirilir.

> [!WARNING]
> `permanently_delete_lfs_files(..., rewrite_history=True)` geri alınamaz. Eski commit'lerde aynı LFS/Xet nesnesine yapılan referanslar da etkilenebilir. Uygulama bu işlemi yalnızca açık kullanıcı onayıyla çalıştırır.

## Yerel çalıştırma

Gereksinimler:

- Windows 10/11 x64
- Python 3.11 veya 3.12
- Read + write yetkili Hugging Face User Access Token

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
$env:PYTHONPATH = "src"
python -m hf_storage
```

## Windows EXE derleme

```powershell
pip install -r requirements-dev.txt pillow
python scripts/generate_icon.py
pytest -q
pyinstaller --noconfirm --clean HFStorage.spec
```

Çıktı:

```text
dist/HFStorage.exe
```

Her `main` push'unda GitHub Actions, testleri çalıştırır ve indirilebilir `HFStorage-Windows-x64` artifact'ını üretir. Bir GitHub Release yayınlandığında `.exe` ve `.zip` otomatik olarak release dosyalarına eklenir.

## Batch upload davranışı

HF Storage dosyaları önce tek bir operasyon listesinde toplar. Her commit en fazla 100 dosya içerir:

```python
operations = [
    CommitOperationAdd(path_in_repo=remote_path, path_or_fileobj=local_path)
    for local_path, remote_path in files
]
api.preupload_lfs_files(repo_id=repo_id, additions=operations)
api.create_commit(repo_id=repo_id, operations=operations, commit_message="Batch upload")
```

Bu sayede örneğin 100 ayrı dosya, 100 ayrı commit yerine **tek commit** olarak gider. Büyük LFS/Xet içerikleri commit öncesi yüklenir; sonrasında tek commit oluşturulur. 100'den fazla dosya, Hugging Face'in depo önerilerine uygun olarak 100'er dosyalık commitlere bölünür.

## Depolama göstergesi

Güncel `huggingface_hub` istemcisindeki `list_user_repos()` her depo için:

- `storage`
- `storage_percent`
- görünürlük
- tür
- son güncelleme

bilgilerini döndürür. Uygulama toplam kullanımı toplar. `storage_percent` mevcutsa namespace kapasitesini otomatik tahmin eder; örneğin 870 GB toplam kullanım `%8.7` ise kapasite yaklaşık 10 TB olarak bulunur. Oran bilgisi yoksa Ayarlar ekranındaki yedek kapasite kullanılır.

## Proje yapısı

```text
src/hf_storage/
├── main.py       # PySide6 arayüzü ve arka plan görevleri
├── service.py    # Hugging Face API, batch upload, silme ve indirme
├── security.py   # Windows DPAPI token kasası
├── models.py     # Veri modelleri ve kapasite hesaplama
└── theme.py      # Profesyonel koyu tema
```

## Lisans

MIT
