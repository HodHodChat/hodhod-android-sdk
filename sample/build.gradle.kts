plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin { jvmToolchain(17) }

android {
    namespace = "chat.hodhod.sample"
    compileSdk = 35
    defaultConfig {
        applicationId = "chat.hodhod.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            // Exercises the SDK consumer ProGuard rules (R8 full mode). Debug-signed so it installs locally.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // `qa`: the minified release configuration plus the debug sources (cleartext to the local server).
    buildTypes.create("qa") {
        initWith(buildTypes.getByName("release"))
        matchingFallbacks += "release"
    }
    sourceSets.getByName("qa").setRoot("src/debug")
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":hodhod-core"))
    implementation(project(":hodhod-ui"))
    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
