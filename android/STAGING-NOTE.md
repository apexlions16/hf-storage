# Android Xet staging

Samsung ve bazı Android üreticilerinde SELinux, uygulamanın kendi açık dosya tanımlayıcısını `/proc/self/fd/<n>` yolu üzerinden Rust tarafında yeniden açmasını `EACCES` ile engelleyebilir.

HF Storage Android bu nedenle seçilen her boş olmayan belgeyi Xet'e vermeden önce uygulamanın özel staging alanına akış halinde kopyalar. Dosya RAM'e bütünüyle alınmaz; 1 MiB tamponla kopyalanır, boyutu doğrulanır ve Xet yalnızca normal, uygulamaya ait bir dosya yolunu okur.

Staging kökü kullanılabilir alana göre `externalCacheDir` veya `cacheDir` içinden seçilir. İşlem tamamlandığında ya da hata verdiğinde geçici dosyalar temizlenir.
