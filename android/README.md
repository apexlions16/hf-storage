# HF Storage Android

`android/`, Windows masaüstü uygulamasından bağımsız derlenen yerel Android istemcisidir.

## Teknoloji

- Kotlin
- Jetpack Compose + Material 3
- OkHttp
- WorkManager `dataSync` foreground upload
- Storage Access Framework
- Android Keystore AES-GCM
- Hugging Face Hub REST ve commit API'leri
- Resmî `hf-xet` Rust çekirdeği için JNI köprüsü

## Mobil özellikler

- Read + write Hugging Face User Access Token ile giriş
- Tokenın Android Keystore ile cihaz üzerinde şifrelenmesi
- Model, Dataset ve Space depolarının tek hesap altında listelenmesi
- Depo dosya ağacı, dosya türü, boyut ve Git/LFS/Xet bilgisinin gösterilmesi
- Dosya ve klasör seçimi
- 100 dosyaya kadar tek commit; 101 ve üzeri dosyada 100'lük commit parçaları
- Sıfır bayt dışındaki bütün yüklemelerde zorunlu Xet parçalama, deduplikasyon ve CAS aktarımı
- Git LFS HTTP upload fallback'i bulunmaması
- WorkManager sayesinde uygulama arka plana geçtiğinde devam eden aktarım
- Android 14-16 için `FOREGROUND_SERVICE_TYPE_DATA_SYNC` desteği
- Dosya indirme
- Normal commit silme ve isteğe bağlı `lfs-files/batch` kalıcı geçmiş temizliği
- Kullanıcı tarafından ayarlanabilir kapasite ve depolama progress barı
- Yeni Model, Dataset veya Docker Space deposu oluşturma
- Tokenı sansürleyen ve `İndirilenler/HFStorage` içine aktarılabilen yükleme hata günlüğü

## Xet mimarisi

Android uygulaması `android/xet-native/` içindeki Rust `cdylib` projesini iki mimari için üretir:

```text
arm64-v8a/libhfstorage_xet.so
x86_64/libhfstorage_xet.so
```

Android belge sağlayıcıları dosyaları çoğunlukla `content://` URI olarak verir. Samsung ve bazı diğer üreticilerin SELinux politikaları, uygulamanın açık dosya tanımlayıcısını `/proc/self/fd/<id>` yolundan Rust tarafında yeniden açmasını `Permission denied (os error 13)` ile engelleyebilir.

Bu nedenle v0.1.3 ve sonrasında her boş olmayan belge Xet'e verilmeden önce uygulamanın özel staging alanına **akış halinde** kopyalanır:

- Kopyalama 1 MiB tampon kullanır; dosyanın tamamı RAM'e alınmaz.
- `externalCacheDir` ve `cacheDir` arasından en fazla kullanılabilir alana sahip konum seçilir.
- Kopyalanan boyut kaynak boyutuyla doğrulanır ve disk tamponu `fsync` ile tamamlanır.
- Xet yalnızca uygulamaya ait normal dosya yolunu okur.
- Upload tamamlandığında veya hata verdiğinde staging klasörü silinir.
- Yeterli boş alan yoksa yükleme başlamadan anlaşılır hata döndürülür.

Hub `preupload` yanıtı son Git commit girdisinin normal blob mu yoksa `lfsFile` pointer mı olacağını belirlemeye devam eder. Ağ aktarımı ve deduplikasyon zorunlu Xet çekirdeği tarafından yapılır; Git LFS HTTP upload fallback'i yoktur.

## Yerel derleme

Gereksinimler:

- Java 17
- Android SDK 35
- Android NDK 27.2.12479018
- Rust stable
- `cargo-ndk`
- Gradle 8.9

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
cd android/xet-native
cargo ndk --target arm64-v8a --target x86_64 --platform 26 \
  --output-dir ../app/src/main/jniLibs build --release
cd ../..
python android/scripts/prepare_rustls_platform_verifier.py
gradle -p android :app:testDebugUnitTest :app:assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Güvenlik notları

Token hiçbir geliştirici sunucusuna gönderilmez. Hub tokenı yalnızca Hugging Face API ve Xet write-token yenileme adresine gönderilir. Xet CAS istekleri, Hub tarafından verilen kısa ömürlü Xet erişim tokenını kullanır. Telemetri kapalıdır.

GitHub sürümü, özel bir üretim imzalama anahtarı repoya eklenmediği için Android debug anahtarıyla imzalanır. APK kurulabilir; ancak gelecekte Play Store veya kesintisiz uygulama güncellemeleri için GitHub Actions Secrets içinde kalıcı bir üretim keystore'u tanımlanmalıdır.
