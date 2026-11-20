# Auricle Proguard / R8 Rules

# JNI Bridge & Rustls Android Platform Verifier
-keep class com.auricle.app.bridge.** { *; }
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepnames class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName *;
}
-keep class com.auricle.app.model.** { *; }
-keep class com.auricle.app.data.*Wrapper$* { *; }
-keep class com.auricle.app.data.*Wrapper { *; }
-keep class com.auricle.app.updater.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# NewPipeExtractor
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }
-dontwarn com.grack.nanojson.**
-keep class com.grack.nanojson.** { *; }
-dontwarn javax.xml.stream.**
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }

# Media3 & ExoPlayer
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.datasource.okhttp.** { *; }
-dontwarn androidx.media3.**

# Coil
-dontwarn coil.**
-keep class coil.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
