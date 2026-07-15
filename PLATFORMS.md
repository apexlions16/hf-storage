# HF Storage platformları

## Windows Desktop

Kaynak kodu şimdilik depo kökündeki Python/PySide6 projesindedir.

- Çalıştırma girişi: `run.py`
- Paketleme: `HFStorage.spec`
- Action: `.github/workflows/build-windows.yml`
- Release serisi: `v0.1.x`
- Çıktı: `HFStorage.exe` ve Windows portable ZIP

## Android

Android sürümü tamamen ayrı bir Gradle projesidir.

- Proje: `android/`
- Application ID: `com.apexlions.hfstorage.mobile`
- Action: `.github/workflows/android-apk.yml`
- Release serisi: `android-v0.1.x`
- Çıktı: `HFStorage-Android-v0.1.0.apk`

İki uygulama aynı Hugging Face hesabını ve depolarını yönetir; ancak arayüz, güvenli token kasası, dosya seçimi, arka plan aktarımı ve paketleme kodları platforma özeldir. Böylece Windows değişiklikleri APK'yı, Android değişiklikleri de EXE'yi gereksiz yere etkilemez.
