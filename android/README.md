# HF Storage Android

`android/`, Windows masaüstü uygulamasından bağımsız derlenen yerel Android istemcisidir.

## Teknoloji

- Kotlin
- Jetpack Compose + Material 3
- OkHttp
- WorkManager foreground upload
- Storage Access Framework
- Android Keystore AES-GCM
- Hugging Face Hub REST, commit ve Git LFS HTTP API'leri

## Mobil özellikler

- Read + write Hugging Face User Access Token ile giriş
- Tokenın Android Keystore ile cihaz üzerinde şifrelenmesi
- Model, Dataset ve Space depolarının tek hesap altında listelenmesi
- Depo dosya ağacı, dosya türü, boyut ve Git/LFS/Xet bilgisinin gösterilmesi
- Dosya ve klasör seçimi
- 100 dosyaya kadar tek commit; 101 ve üzeri dosyada 100'lük commit parçaları
- Büyük dosyalarda LFS basic ve multipart upload
- WorkManager sayesinde uygulama arka plana geçtiğinde devam eden aktarım
- Dosya indirme
- Normal commit silme ve isteğe bağlı `lfs-files/batch` kalıcı geçmiş temizliği
- Kullanıcı tarafından ayarlanabilir kapasite ve depolama progress barı
- Yeni Model, Dataset veya Docker Space deposu oluşturma

## Derleme

```bash
gradle -p android :app:testDebugUnitTest :app:assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Güvenlik notları

Token hiçbir geliştirici sunucusuna gönderilmez. Android uygulaması yalnızca `https://huggingface.co` ve Hugging Face'in LFS yükleme sırasında verdiği imzalı depolama URL'leriyle iletişim kurar.

İlk GitHub sürümü, özel bir üretim imzalama anahtarı repoya eklenmediği için Android debug anahtarıyla imzalanır. APK kurulabilir; ancak gelecekte Play Store veya kesintisiz uygulama güncellemeleri için GitHub Actions Secrets içinde kalıcı bir üretim keystore'u tanımlanmalıdır.
