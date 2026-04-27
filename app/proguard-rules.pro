# 1. Menghilangkan "Unknown Source"
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# 2. JAGA SEMUA KELAS DI PAKET UTAMA (PENTING UNTUK DYNAMIC LOADING)
# Kita mematikan optimasi dan obfuscation untuk paket ini agar JAR eksternal bisa memanggil method dengan nama asli.
-keep class com.bluestacks.fpsoverlay.** { *; }
-keep interface com.bluestacks.fpsoverlay.** { *; }
-dontobfuscate
-dontoptimize

# 3. Jaga CameraX & ListenableFuture (Fix NoSuchMethodError)
# R8 seringkali mengubah signature ListenableFuture yang menyebabkan crash saat dipanggil dari DEX luar.
-keep class androidx.camera.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-dontwarn androidx.camera.**

# 4. Jaga WebRTC
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
-dontwarn org.webrtc.**

# 5. Jaga Supabase & Networking
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.java_websocket.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.java_websocket.**

# 6. Jaga Native Methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# 7. SLF4J
-dontwarn org.slf4j.**

# 8. Android Support & AndroidX
-keep class android.support.** { *; }
-keep class androidx.** { *; }
-dontwarn android.support.**
-dontwarn androidx.**
