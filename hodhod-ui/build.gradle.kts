plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "chat.hodhod.sdk.ui"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    api(project(":hodhod-core"))
    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(bom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Dokka (bundled with AGP) cannot read the sealed-class metadata of the Compose
// dependencies (ASM <9). The UI module is a Compose screen host, the documented
// API surface lives in :hodhod-core, so the javadoc jar of this module is empty.
tasks.matching { it.name == "javaDocReleaseGeneration" }.configureEach { enabled = false }

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "hodhod-ui"
                version = project.version.toString()
                pom {
                    name.set("Hodhod Android SDK - UI")
                    description.set("Jetpack Compose chat UI of the Hodhod customer-support SDK.")
                    url.set("https://github.com/HodHodChat/hodhod-android-sdk")
                    inceptionYear.set("2026")
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
                    organization {
                        name.set("HodHodChat")
                        url.set("https://github.com/HodHodChat")
                    }
                    developers {
                        developer {
                            id.set("HodHodChat")
                            name.set("HodHodChat")
                            organization.set("HodHodChat")
                            organizationUrl.set("https://github.com/HodHodChat")
                        }
                    }
                    scm {
                        url.set("https://github.com/HodHodChat/hodhod-android-sdk")
                        connection.set("scm:git:git://github.com/HodHodChat/hodhod-android-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/HodHodChat/hodhod-android-sdk.git")
                    }
                }
            }
        }
    }
}
