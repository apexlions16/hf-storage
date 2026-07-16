# HF Storage platformları

## Windows Desktop

Kaynak kodu şimdilik depo kökündeki Python/PySide6 projesindedir.

- Çalıştırma girişi: `run.py`
- Paketleme: `HFStorage.spec`
- Action: `.github/workflows/build-windows.yml`
- Release serisi: `v0.1.x`
- Çıktı: `HFStorage.exe` ve Windows portable ZIP

## Android

Android sürümü tamamen ayrı bir Gradle + Rust projesidir.

- Kotlin/Compose proje kökü: `android/`
- Native Xet köprüsü: `android/xet-native/`
- Application ID: `com.apexlions.hfstorage.mobile`
- Action: `.github/workflows/android-apk.yml`
- Release serisi: `android-v0.1.x`
- Güncel çıktı: `HFStorage-Android-v0.1.1.apk`
- Desteklenen native mimariler: `arm64-v8a`, `x86_64`
- Yükleme backend'i: zorunlu Hugging Face Xet; Git LFS upload fallback'i yok

İki uygulama aynı Hugging Face hesabını ve depolarını yönetir; ancak arayüz, güvenli token kasası, dosya seçimi, arka plan aktarımı ve paketleme kodları platforma özeldir. Windows ve Android release etiketleri ayrıdır. Android kaynak değişiklikleri Windows EXE release'inin içine APK koymaz; Android Action yalnızca `android/**`, kendi workflow'u veya bu platform belgesi değiştiğinde çalışır.
