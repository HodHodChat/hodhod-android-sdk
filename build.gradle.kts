plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Shared publishing coordinates. Modules read project.group / project.version.
//  * Default group: chat.hodhod (e.g. a future Maven Central release).
//  * On JitPack (env JITPACK=true) the multi-module group is com.github.<owner>.<repo>.
//  * Version: `-Pversion=...` (JitPack passes the tag) wins over the default below.
val sdkDefaultVersion = "1.0.0-beta03"
val sdkGroup = if (System.getenv("JITPACK") == "true") "com.github.HodHodChat.hodhod-android-sdk" else "chat.hodhod"
val sdkVersion = (findProperty("version") as? String)?.takeIf { it != "unspecified" } ?: sdkDefaultVersion
allprojects {
    group = sdkGroup
    version = sdkVersion
}
