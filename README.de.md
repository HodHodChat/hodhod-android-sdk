# Hodhod Android SDK

Natives Support-Chat-SDK (Kotlin + Jetpack Compose) für Android: Live-Chat, Tickets, Vorab-Formular, Zufriedenheitsumfrage, Anhänge, Dunkelmodus und sechs Sprachen (فارسی, English, العربية, Deutsch, Español, Français) mit vollständiger RTL-Unterstützung. Es nutzt dieselbe öffentliche Widget-API wie das Hodhod-Website-Widget.

## Voraussetzungen

Android 7.0+ (minSdk 24), Kotlin 2.x, Jetpack Compose, Java 17. Der Server muss Hodhod sein (HTTPS; `allowCleartext = true` nur für lokale Entwicklung).

## Installation

Fügen Sie das UI-Modul hinzu (es bringt das Core-Modul mit):

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
    implementation("com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta04") // brings hodhod-core
}
```

Quellcode: <https://github.com/HodHodChat/hodhod-android-sdk>. [JitPack](https://jitpack.io/#HodHodChat/hodhod-android-sdk) baut das Paket aus dem Git-Tag `1.0.0-beta04` (der Tag muss im Repository existieren; siehe `docs/RELEASING.md`). Nur das Core-Modul: `com.github.HodHodChat.hodhod-android-sdk:hodhod-core:1.0.0-beta04`.

## Konfiguration

Einmalig konfigurieren, z. B. in `Application.onCreate` (das Netzwerk wird erst bei Nutzung des Chats verwendet):

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hodhod.configure(this, HodhodConfig(baseUrl = "https://hodhod.chat", websiteToken = "YOUR_WEBSITE_TOKEN"))
    }
}
```

## Chat öffnen

```kotlin
Hodhod.open(context)            // full-screen HodhodChatActivity (deep link: hodhod://chat)
```

## In Compose einbetten

Oder den Chat bzw. den schwebenden Button in Ihre Compose-Oberfläche einbetten. Das Badge für ungelesene Nachrichten aktualisiert sich, solange die App geöffnet ist:

```kotlin
@Composable
fun SupportScreen(onClose: () -> Unit) = HodhodChat(Modifier.fillMaxSize(), onClose = onClose)

@Composable
fun Home() = Box(Modifier.fillMaxSize()) {
    // ... your content ...
    HodhodBubble(Modifier.align(Alignment.BottomEnd).padding(16.dp)) // unread badge included
}
```

## Chatbot-Abläufe (Flows)

Hat der Posteingang einen aktiven [Chatbot-Ablauf](https://hodhod.chat/features/chatbot-flows), führt das SDK ihn auf dem Startbildschirm nativ aus, ganz ohne zusätzlichen Code: alle 17 Knotentypen, Variablen und Bedingungen, Eingabeprüfung, Bewertungen sowie die Übergabe an einen Live-Mitarbeiter **oder als Ticket**, dazu dieselben Flow-Analysen wie im Web-Widget. Ist die Flow-Einstellung `require_flow` aktiv, bleibt die direkte Karte „Unterhaltung starten“ verborgen, solange ein Ablauf verfügbar ist (sie erscheint wieder, wenn der Ablauf nicht geladen werden kann). Die Engine wurde mit 40 Paritätsszenarien gegen die Web-Engine geprüft (gleiche Schritte, Variablen und Ereignisse); die Bildschirme wurden im Emulator auf Persisch (RTL) und Englisch verifiziert.

## Meine Tickets

Besucher sehen immer **alle** ihre Tickets, offene und geschlossene, unabhängig vom Kontaktmodus des Posteingangs (Chat, Ticket, beides, Ticket außerhalb der Geschäftszeiten). Der Startbildschirm zeigt eine kompakte Zeile „Meine Tickets“ mit der Anzahl offener Tickets, sobald es mindestens ein Ticket gibt, auch während eines Live-Chats. Die Liste hat die Filter Offen / Geschlossen / Alle mit Zählern (Standard: Offen, wenn es offene gibt, sonst Alle), leere Zustände je Filter, Statusabzeichen, relative Zeiten, Pull-to-Refresh und Nachladen. Von einem Agenten aus einem Chat erstellte Tickets sind mit „Aus dem Chat“ markiert; ist es der aktuelle Live-Chat, öffnet ein Tipp den Chat. In reinen Ticket-Postfächern ist die Liste der Startbildschirm mit prominentem Button „Neues Ticket“; in reinen Chat-Postfächern ist der Button ausgeblendet, die Liste bleibt erreichbar. Benötigt einen Server mit `GET /api/v1/widget/tickets?status=&page=&per_page=` und `/tickets/summary`; ältere Server werden clientseitig gezählt und gefiltert.

## Ankündigungen

Ein Posteingang kann bis zu zwei Ankündigungen veröffentlichen (in den Posteingangs-Einstellungen des Dashboards). Sie erscheinen ganz oben auf dem Startbildschirm: Home, das Ticket-Panel, wenn es Home ersetzt, und das Pre-Chat-Formular, über dem Flow, den Startkarten und der Zeile „Meine Tickets“; Störungshinweise folgen danach. Ein *Hinweis* ist ein gelbes Informationsbanner, eine *Warnung* ein rotes Banner mit Symbol; beide sind im hellen und dunklen Design gut lesbar. Der Inhalt ist formatierter Text (fett, Links) und optional ein Bild. Links sind auf `http`, `https`, `mailto` und `tel` beschränkt, werden mit `ACTION_VIEW` geöffnet (Browser, Telefon, Mail-App; nie ein WebView), normaler Text wird nie automatisch verlinkt. Bilder müssen `https` sein, haben eine feste Maximalhöhe, ihr Alternativtext wird von TalkBack gelesen, sie können verlinkt sein und werden bei Ladefehlern ausgeblendet. Schließbare Ankündigungen haben eine Schaltfläche (TalkBack: „Schließen“); die Wahl wird pro `(websiteToken, id, updated_at)` auf dem Gerät gespeichert, eine bearbeitete Ankündigung erscheint also erneut.

```kotlin
val items by Hodhod.repository.announcements.collectAsState()   // noch nicht geschlossen, mit der Widget-Konfiguration aktualisiert
Hodhod.repository.dismissAnnouncement(items.first().id)           // bei nicht schließbaren Ankündigungen ignoriert
// WidgetConfig.announcements enthält die Rohliste; ältere Server senden keine (leere Liste)
```

Ohne Server ausprobieren: `adb shell am start -n chat.hodhod.sample/.MainActivity --es token x --es fake announcements`.

## Benutzer identifizieren

Angemeldete Benutzer identifizieren (optional). `identifierHash` ist der HMAC-SHA256 der Kennung mit dem HMAC-Token des Postfachs und muss von **Ihrem Backend** berechnet werden, nie in der App:

```kotlin
Hodhod.identify(
    HodhodUser(identifier = "user-42", identifierHash = hmacFromYourBackend, name = "Ali", email = "ali@example.com")
) { result -> /* Result<Unit> */ }

Hodhod.logout() // forget the visitor on this device
```

## Sprache, Design und Farbe

Alle Texte, die Richtung (RTL/LTR) und Datumsangaben folgen der gewählten Sprache; die Schrift Vazirmatn ist enthalten. Optionen:

```kotlin
HodhodConfig(
    baseUrl = "https://hodhod.chat",
    websiteToken = "YOUR_WEBSITE_TOKEN",
    locale = "fa",                       // fa, en, ar, de, es, fr (null = device language)
    darkMode = DarkMode.AUTO,            // AUTO, LIGHT, DARK
    accentColorOverride = 0xFF7A4FD1,    // null = inbox widget colour
)
```

## Texte (sechs Sprachen)

Alle Texte stammen aus den Sprachdateien des Web-Widgets und werden für die sechs Sprachen in Android-Ressourcen erzeugt (andere Sprachen fallen auf Englisch zurück):

```bash
python3 tools/gen_strings.py            # regenerate hodhod-ui/src/main/res/values*/hodhod_strings.xml
./gradlew :hodhod-ui:testDebugUnitTest  # fails if a locale misses a key
```

## Beispiel-App

Das Modul `sample` konfiguriert das SDK über ein Formular (Basis-URL, Website-Token, Sprache, Design), identifiziert einen Benutzer und öffnet den Chat:

```bash
./gradlew :sample:installDebug          # emulator: base URL http://10.0.2.2:3000
```

## Weitere Sprachen

[English](README.md) · [فارسی](README.fa.md) · [العربية](README.ar.md) · [Español](README.es.md) · [Français](README.fr.md)
