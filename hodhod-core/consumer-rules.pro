# Hodhod SDK consumer rules (R8/ProGuard)
# kotlinx.serialization: keep generated serializers of SDK models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class chat.hodhod.sdk.**$$serializer { *; }
-keepclassmembers class chat.hodhod.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class chat.hodhod.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# UI module is looked up by name from core (Hodhod.open) — keep the activity entry point.
-keep class chat.hodhod.sdk.ui.HodhodChatActivity { *; }
# OkHttp/Okio optional platforms
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# EncryptedSharedPreferences -> Tink references compile-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
