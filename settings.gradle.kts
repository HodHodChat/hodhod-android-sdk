pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's read-only Maven Central mirror first: JitPack's shared build IPs get HTTP 429 from repo.maven.apache.org
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's read-only Maven Central mirror first: JitPack's shared build IPs get HTTP 429 from repo.maven.apache.org
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "hodhod-android-sdk"
include(":hodhod-core", ":hodhod-ui", ":sample")
