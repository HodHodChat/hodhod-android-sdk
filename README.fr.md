# SDK Hodhod pour Android

Chat de support natif (Kotlin + Jetpack Compose) pour Android : chat en direct, tickets, formulaire préalable, enquête de satisfaction, pièces jointes, mode sombre et six langues (فارسی, English, العربية, Deutsch, Español, Français) avec prise en charge complète du RTL. Il utilise la même API publique que le widget web Hodhod.

## Prérequis

Android 7.0+ (minSdk 24), Kotlin 2.x, Jetpack Compose, Java 17. Le serveur doit être Hodhod (HTTPS ; `allowCleartext = true` uniquement en développement local).

## Installation

Ajoutez le module d’interface (il inclut le module core) :

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

Code source : <https://github.com/HodHodChat/hodhod-android-sdk>. [JitPack](https://jitpack.io/#HodHodChat/hodhod-android-sdk) construit le paquet à partir du tag git `1.0.0-beta04` (le tag doit exister dans le dépôt ; voir `docs/RELEASING.md`). Module core seul : `com.github.HodHodChat.hodhod-android-sdk:hodhod-core:1.0.0-beta04`.

## Configuration

Configurez une seule fois, par exemple dans `Application.onCreate` (aucun accès réseau tant que le chat n’est pas utilisé) :

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hodhod.configure(this, HodhodConfig(baseUrl = "https://hodhod.chat", websiteToken = "YOUR_WEBSITE_TOKEN"))
    }
}
```

## Ouvrir le chat

```kotlin
Hodhod.open(context)            // full-screen HodhodChatActivity (deep link: hodhod://chat)
```

## Intégrer dans Compose

Ou intégrez le chat ou le bouton flottant dans votre interface Compose. Le badge de messages non lus se met à jour tant que l’application est ouverte :

```kotlin
@Composable
fun SupportScreen(onClose: () -> Unit) = HodhodChat(Modifier.fillMaxSize(), onClose = onClose)

@Composable
fun Home() = Box(Modifier.fillMaxSize()) {
    // ... your content ...
    HodhodBubble(Modifier.align(Alignment.BottomEnd).padding(16.dp)) // unread badge included
}
```

## Parcours de chatbot (flows)

Si la boîte de réception a un [parcours de chatbot](https://hodhod.chat/features/chatbot-flows) actif, le SDK l'exécute nativement sur l'écran d'accueil, sans code supplémentaire : les 17 types de nœuds, variables et conditions, validation des saisies, notes, et transfert à un agent en direct **ou sous forme de ticket**, avec les mêmes analyses de parcours que le widget web. Si l'option `require_flow` du parcours est activée, la carte directe « Démarrer la conversation » reste masquée tant qu'un parcours est disponible (elle réapparaît si le parcours ne peut pas être chargé). Le moteur a été comparé au moteur web avec 40 scénarios de parité (mêmes étapes, variables et événements) ; les écrans ont été vérifiés sur émulateur en persan (RTL) et en anglais.

## Mes tickets

Les visiteurs voient toujours **tous** leurs tickets, ouverts et fermés, quel que soit le mode de contact de la boîte (chat, ticket, les deux, ticket hors horaires). L'accueil affiche une ligne compacte « Mes tickets » avec le nombre de tickets ouverts dès qu'il existe au moins un ticket, même pendant un chat en direct. La liste propose les filtres Ouverts / Fermés / Tous avec compteurs (par défaut Ouverts s'il y en a, sinon Tous), des états vides par filtre, des badges de statut, des heures relatives, le tirer pour actualiser et le chargement de la suite. Les tickets qu'un agent a créés à partir d'un chat portent l'étiquette « Depuis le chat » ; s'il s'agit du chat en direct actuel, un appui ouvre le chat. Dans les boîtes uniquement ticket, la liste est l'accueil avec un bouton bien visible « Nouveau ticket » ; dans les boîtes uniquement chat, le bouton est masqué mais la liste reste accessible. Nécessite un serveur avec `GET /api/v1/widget/tickets?status=&page=&per_page=` et `/tickets/summary` ; les serveurs plus anciens sont comptés et filtrés côté client.

## Annonces

Une boîte de réception peut publier jusqu’à deux annonces (dans les réglages de la boîte du tableau de bord). Elles apparaissent tout en haut de l’écran de démarrage : Home, le panneau de tickets lorsqu’il tient lieu de Home et le formulaire de pré-chat, au-dessus du parcours, des cartes de démarrage et de la ligne « Mes tickets » ; les avis d’incident viennent après. Une *information* est un bandeau jaune, une *alerte* un bandeau rouge avec icône ; tous deux restent lisibles en clair et en sombre. Le contenu est du texte enrichi (gras, liens) et une image facultative. Les liens sont limités à `http`, `https`, `mailto` et `tel`, s’ouvrent avec `ACTION_VIEW` (navigateur, numéroteur, appli mail ; jamais une WebView) et le texte brut n’est jamais transformé en lien automatiquement. Les images doivent être en `https`, ont une hauteur maximale fixe, leur texte alternatif est lu par TalkBack, elles peuvent porter un lien et sont masquées si le chargement échoue. Les annonces fermables ont un bouton de fermeture (libellé TalkBack : « Fermer ») ; le choix est mémorisé sur l’appareil par `(websiteToken, id, updated_at)`, une annonce modifiée réapparaît donc.

```kotlin
val items by Hodhod.repository.announcements.collectAsState()   // non fermées, actualisées avec la configuration du widget
Hodhod.repository.dismissAnnouncement(items.first().id)           // ignoré pour les annonces non fermables
// WidgetConfig.announcements conserve la liste brute ; les anciens serveurs n’en envoient pas (liste vide)
```

À essayer sans serveur : `adb shell am start -n chat.hodhod.sample/.MainActivity --es token x --es fake announcements`.

## Identifier l’utilisateur

Identifiez les utilisateurs connectés (facultatif). `identifierHash` est le HMAC-SHA256 de l’identifiant avec le jeton HMAC de la boîte et doit être calculé par **votre backend**, jamais dans l’application :

```kotlin
Hodhod.identify(
    HodhodUser(identifier = "user-42", identifierHash = hmacFromYourBackend, name = "Ali", email = "ali@example.com")
) { result -> /* Result<Unit> */ }

Hodhod.logout() // forget the visitor on this device
```

## Langue, thème et couleur

Tous les textes, la direction (RTL/LTR) et les dates suivent la langue choisie ; la police Vazirmatn est incluse. Options :

```kotlin
HodhodConfig(
    baseUrl = "https://hodhod.chat",
    websiteToken = "YOUR_WEBSITE_TOKEN",
    locale = "fa",                       // fa, en, ar, de, es, fr (null = device language)
    darkMode = DarkMode.AUTO,            // AUTO, LIGHT, DARK
    accentColorOverride = 0xFF7A4FD1,    // null = inbox widget colour
)
```

## Textes (six langues)

Tous les textes proviennent des fichiers de langue du widget web et sont générés en ressources Android pour les six langues (les autres retombent sur l’anglais) :

```bash
python3 tools/gen_strings.py            # regenerate hodhod-ui/src/main/res/values*/hodhod_strings.xml
./gradlew :hodhod-ui:testDebugUnitTest  # fails if a locale misses a key
```

## Application d’exemple

Le module `sample` configure le SDK depuis un formulaire (URL de base, jeton du site, langue, thème), identifie un utilisateur et ouvre le chat :

```bash
./gradlew :sample:installDebug          # emulator: base URL http://10.0.2.2:3000
```

## Autres langues

[English](README.md) · [فارسی](README.fa.md) · [العربية](README.ar.md) · [Español](README.es.md) · [Deutsch](README.de.md)
