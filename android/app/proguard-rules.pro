-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**

# JNI resolves the native method by this exact class and method name.
-keep class com.apexlions.hfstorage.mobile.data.XetNative { *; }
-keep class com.apexlions.hfstorage.mobile.data.XetNativeUploadResult { *; }
