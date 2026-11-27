# Keep source line info for clear crash stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,Signature,InnerClasses,EnclosingMethod

# Kotlin Metadata (needed by Moshi KotlinJsonAdapterFactory, Retrofit suspend functions, default args)
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public *;
}

# Moshi & KSP-generated JsonAdapters + DTOs
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class *JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
    public <init>(com.squareup.moshi.Moshi, java.lang.reflect.Type[]);
}
-keep class com.example.data.quran.** { *; }
-keep class com.example.data.audio.** { *; }
-keep class com.example.data.integrity.** { *; }
-keep class com.example.data.prefs.** { *; }

# Retrofit 2 interfaces & suspend Continuations
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.Platform$Java8
-dontwarn kotlin.Unit

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# Media3 ExoPlayer & Playback Service
-keep class androidx.media3.** { *; }
-keep class com.example.player.** { *; }
-keep class com.example.data.prayer.** { *; }
-keep class com.example.ui.** { *; }
-keep public class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}


