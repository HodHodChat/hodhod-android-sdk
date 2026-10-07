# Publishing

Repository: <https://github.com/HodHodChat/hodhod-android-sdk>. Distribution: JitPack (see below). `./gradlew :hodhod-core:publishReleasePublicationToMavenLocal :hodhod-ui:publishReleasePublicationToMavenLocal` produces AAR + sources + javadoc + POM + Gradle module metadata in `~/.m2`.

Coordinates depend on where the build runs (root `build.gradle.kts`):

* On JitPack (`JITPACK=true`): `com.github.HodHodChat.hodhod-android-sdk:hodhod-core` and `:hodhod-ui`, version = the git tag (passed as `-Pversion=$VERSION`).
* Elsewhere (local, CI, a future Maven Central release): group `chat.hodhod`, version `1.0.0-beta04` unless `-Pversion` is given.

## Option 1: Maven Central (recommended for a public SDK)

1. Register the namespace `chat.hodhod` in the Sonatype Central Portal (verify the `hodhod.chat` domain with a DNS TXT record).
2. Create a GPG key, publish its public part to a key server, and keep the private key outside the repo.
3. Add a publishing plugin (for example `com.vanniktech.maven.publish`) or the Central Portal upload API. Provide credentials and the signing key through `~/.gradle/gradle.properties` or CI secrets (`mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyPassword`), never in the repository.
4. Central requires: license, developer and SCM entries in the POM, sources and javadoc jars, and signatures. The UI module ships an empty javadoc jar (see `docs/troubleshooting.md`).

## Option 2: GitHub Packages

Add a `maven { url = uri("https://maven.pkg.github.com/<owner>/<repo>"); credentials { username = ...; password = ... } }` repository to the `publishing` block and run `publish` with a token that has `write:packages`. Consumers also need a token to read (even for public repositories), so this suits private/internal distribution.

## Option 3: JitPack (configured)

`jitpack.yml` (JDK 17) runs `./gradlew -Pversion=$VERSION :hodhod-core:publishReleasePublicationToMavenLocal :hodhod-ui:publishReleasePublicationToMavenLocal -x test`. JitPack builds a git tag of the public GitHub repo and serves the modules as `com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:<tag>` and `...:hodhod-core:<tag>`; consumers add `maven { url = uri("https://jitpack.io") }`. No credentials are needed. The tag `1.0.0-beta04` must exist before the README snippet works; the exact commands are in `docs/RELEASING.md`. Status page: <https://jitpack.io/#HodHodChat/hodhod-android-sdk>.

The sample app is not built by the JitPack command, and the build has no dependency on files outside this repository (generated `hodhod_strings.xml` files and fonts are committed; `tools/` needs the Rails tree only for maintainers).

## Before the first release

* `LICENSE` (MIT, "HodHodChat") and the POM SCM/developer data are set; adjust the copyright holder if needed.
* Decide whether to sign release AARs and bump to a non-beta version.
* Run the full check: `./gradlew clean :hodhod-core:testDebugUnitTest :hodhod-ui:testDebugUnitTest :sample:assembleRelease lint`.
