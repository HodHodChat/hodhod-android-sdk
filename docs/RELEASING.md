# Releasing (git-ready checklist)

Run these yourself, from the project root (the directory that contains `gradlew`, i.e. `android-sdk/`). Nothing here has been executed for you.

## 0. Pre-flight

```bash
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/corretto-17.0.11/Contents/Home
./gradlew :hodhod-core:testDebugUnitTest :hodhod-ui:testDebugUnitTest :sample:assembleDebug lint
git --version && ls -l gradlew   # gradlew must be executable (-rwxr-xr-x)
```

## 1. First commit

`android-sdk/` must be the repository root (do not run `git init` in the parent `bolbol` directory).

```bash
cd /Users/nobitex/bolbol/android-sdk
git init -b main
git remote add origin git@github.com:HodHodChat/hodhod-android-sdk.git   # or https://github.com/HodHodChat/hodhod-android-sdk.git
git add -A
git status                      # check: no local.properties, build/, .gradle/, *.jks; gradle/wrapper/gradle-wrapper.jar IS listed
git commit -m "Hodhod Android SDK 1.0.0-beta03"
```

(Create the empty public repository `HodHodChat/hodhod-android-sdk` on GitHub first, without README/license.)

## 2. Tag and push

```bash
git tag -a 1.0.0-beta03 -m "1.0.0-beta03"
git push -u origin main
git push origin 1.0.0-beta03
```

The tag name is the version consumers use, so it must be exactly `1.0.0-beta03`.

## 3. Trigger / check the JitPack build

1. Open <https://jitpack.io/#HodHodChat/hodhod-android-sdk>, find `1.0.0-beta03` under "Releases" and click "Get it". Check the build log: it must end green and list `hodhod-core` and `hodhod-ui`.
2. Or from a shell (the first request triggers the build; repeat until it returns the POM):

```bash
curl -sI https://jitpack.io/com/github/HodHodChat/hodhod-android-sdk/hodhod-ui/1.0.0-beta03/hodhod-ui-1.0.0-beta03.pom | head -1
```

3. Verify as a consumer: a fresh Android project with `maven { url = uri("https://jitpack.io") }` and `implementation("com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta03")`.

If the build fails, fix, commit, and publish a new tag (e.g. `1.0.0-beta03`); do not move a published tag.

## 4. Optional: GitHub Release

```bash
gh release create 1.0.0-beta03 --title "1.0.0-beta03" --prerelease --notes-file CHANGELOG.md
```

## 5. After publishing

* Check the CI run (`.github/workflows/ci.yml`) on the Actions tab.
* Landing page and READMEs already point at the JitPack coordinates; nothing else to change.
