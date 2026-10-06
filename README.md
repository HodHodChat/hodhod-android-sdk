# Hodhod Android SDK

Native (Kotlin + Jetpack Compose) customer-support chat for Android: live chat, tickets, pre-chat form, satisfaction survey, attachments, dark mode and six languages (فارسی, English, العربية, Deutsch, Español, Français) with full RTL support. It talks to the same public widget API as the Hodhod website widget.

## Requirements

Android 7.0+ (minSdk 24), Kotlin 2.x, Jetpack Compose, Java 17. The server must be Hodhod (HTTPS; `allowCleartext = true` only for local development).

## Install

Add the UI module (it depends on the core module):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta02") // brings hodhod-core
}
```

Source: <https://github.com/HodHodChat/hodhod-android-sdk>. Built by [JitPack](https://jitpack.io/#HodHodChat/hodhod-android-sdk) from the git tag `1.0.0-beta02` (the tag must exist in the repository; see `docs/RELEASING.md`). The core module alone: `com.github.HodHodChat.hodhod-android-sdk:hodhod-core:1.0.0-beta02`.

## Configure

Configure once, for example in `Application.onCreate` (nothing touches the network until the chat is used):

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hodhod.configure(this, HodhodConfig(baseUrl = "https://hodhod.chat", websiteToken = "YOUR_WEBSITE_TOKEN"))
    }
}
```

## Open the chat

```kotlin
Hodhod.open(context)            // full-screen HodhodChatActivity (deep link: hodhod://chat)
```

## Embed in Compose

Or embed the chat or the floating launcher in your own Compose UI. The unread badge updates while the app is open:

```kotlin
@Composable
fun SupportScreen(onClose: () -> Unit) = HodhodChat(Modifier.fillMaxSize(), onClose = onClose)

@Composable
fun Home() = Box(Modifier.fillMaxSize()) {
    // ... your content ...
    HodhodBubble(Modifier.align(Alignment.BottomEnd).padding(16.dp)) // unread badge included
}
```

## Chatbot flows

If the inbox has an active [chatbot flow](https://hodhod.chat/features/chatbot-flows), the SDK runs it natively on the start screen, with no extra code: all 17 node kinds, variables and conditions, input validation, ratings, and handoff to a live agent **or as a ticket**, plus the same flow analytics as the web widget. If the flow setting `require_flow` is on, the direct "start conversation" card is hidden while a flow is available (it comes back if the flow cannot load). The engine is checked against the web engine with 40 parity scenarios (identical steps, variables and events); the screens were verified on an emulator in Persian (RTL) and English.

## Identify the user

Identify signed-in users (optional). `identifierHash` is the HMAC-SHA256 of the identifier with the inbox HMAC token and must be computed by **your backend**, never in the app:

```kotlin
Hodhod.identify(
    HodhodUser(identifier = "user-42", identifierHash = hmacFromYourBackend, name = "Ali", email = "ali@example.com")
) { result -> /* Result<Unit> */ }

Hodhod.logout() // forget the visitor on this device
```

## Language, theme and colour

All text, direction (RTL/LTR) and dates follow the chosen language; fonts (Vazirmatn) are bundled. Options:

```kotlin
HodhodConfig(
    baseUrl = "https://hodhod.chat",
    websiteToken = "YOUR_WEBSITE_TOKEN",
    locale = "fa",                       // fa, en, ar, de, es, fr (null = device language)
    darkMode = DarkMode.AUTO,            // AUTO, LIGHT, DARK
    accentColorOverride = 0xFF7A4FD1,    // null = inbox widget colour
)
```

## Strings (six languages)

All texts come from the web widget locale files and are generated into Android resources for the six supported languages (other languages fall back to English):

```bash
python3 tools/gen_strings.py            # maintainers only (needs the web widget locale files); output is committed. Regenerates hodhod-ui/src/main/res/values*/hodhod_strings.xml
./gradlew :hodhod-ui:testDebugUnitTest  # fails if a locale misses a key
```

## Sample app

The `sample` module configures the SDK from a form (base URL, website token, language, theme), identifies a user and opens the chat:

```bash
./gradlew :sample:installDebug          # emulator: base URL http://10.0.2.2:3000
```

## Other languages

[فارسی](README.fa.md) · [العربية](README.ar.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md)
