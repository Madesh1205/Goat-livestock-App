# Kotlinx Serialization Rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowshrinking class * {
    <fields>;
}
-keepclassmembers class com.ammalfarm.adusanthai.data.dto.** { *; }
-keepclassmembers class com.ammalfarm.adusanthai.model.** { *; }
-keep class com.ammalfarm.adusanthai.data.dto.** { *; }
-keep class com.ammalfarm.adusanthai.model.** { *; }

# Supabase & Ktor Rules
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn kotlinx.coroutines.internal.**
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }

# Retrofit, OkHttp & Moshi
-keepattributes Signature
-keepattributes Exceptions
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Room Database
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# Coil Image Loading
-keep class coil.** { *; }

# Firebase & Play Services
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# AndroidX Navigation Compose
-keep class androidx.navigation.** { *; }
-keepclassmembers class * extends androidx.navigation.NavType { *; }

# Sentry
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# Optimize Log calls in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

