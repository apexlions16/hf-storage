-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**

# JNI resolves the native method by this exact class and method name.
-keep class com.apexlions.hfstorage.mobile.data.XetNative { *; }
-keep class com.apexlions.hfstorage.mobile.data.XetNativeUploadResult { *; }

# rustls-platform-verifier loads its Android TrustManager bridge from Rust/JNI.
# R8 cannot see that native reference and would otherwise remove the classes.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
