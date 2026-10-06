plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "chat.hodhod.sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }
    buildFeatures { buildConfig = true }
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
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "hodhod-core"
                version = project.version.toString()
                pom {
                    name.set("Hodhod Android SDK - core")
                    description.set("Headless core (API client, ActionCable, repository) of the Hodhod customer-support SDK.")
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
