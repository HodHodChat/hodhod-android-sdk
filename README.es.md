# SDK de Hodhod para Android

Chat de soporte nativo (Kotlin + Jetpack Compose) para Android: chat en vivo, tickets, formulario previo, encuesta de satisfacción, archivos adjuntos, modo oscuro y seis idiomas (فارسی, English, العربية, Deutsch, Español, Français) con soporte RTL completo. Usa la misma API pública del widget web de Hodhod.

## Requisitos

Android 7.0+ (minSdk 24), Kotlin 2.x, Jetpack Compose, Java 17. El servidor debe ser Hodhod (HTTPS; `allowCleartext = true` solo para desarrollo local).

## Instalación

Añade el módulo de interfaz (incluye el módulo core):

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
    implementation("com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta01") // brings hodhod-core
}
```

Código fuente: <https://github.com/HodHodChat/hodhod-android-sdk>. [JitPack](https://jitpack.io/#HodHodChat/hodhod-android-sdk) compila el paquete a partir de la etiqueta git `1.0.0-beta01` (la etiqueta debe existir en el repositorio; véase `docs/RELEASING.md`). Solo el módulo core: `com.github.HodHodChat.hodhod-android-sdk:hodhod-core:1.0.0-beta01`.

## Configuración

Configura una sola vez, por ejemplo en `Application.onCreate` (no se usa la red hasta que se utiliza el chat):

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hodhod.configure(this, HodhodConfig(baseUrl = "https://hodhod.chat", websiteToken = "YOUR_WEBSITE_TOKEN"))
    }
}
```

## Abrir el chat

```kotlin
Hodhod.open(context)            // full-screen HodhodChatActivity (deep link: hodhod://chat)
```

## Integrar en Compose

O integra el chat o el botón flotante en tu interfaz Compose. La insignia de mensajes sin leer se actualiza mientras la app está abierta:

```kotlin
@Composable
fun SupportScreen(onClose: () -> Unit) = HodhodChat(Modifier.fillMaxSize(), onClose = onClose)

@Composable
fun Home() = Box(Modifier.fillMaxSize()) {
    // ... your content ...
    HodhodBubble(Modifier.align(Alignment.BottomEnd).padding(16.dp)) // unread badge included
}
```

## Flujos de chatbot

Si la bandeja tiene un [flujo de chatbot](https://hodhod.chat/features/chatbot-flows) activo, el SDK lo ejecuta de forma nativa en la pantalla de inicio, sin código adicional: los 17 tipos de nodo, variables y condiciones, validación de entradas, valoraciones y traspaso a un agente en vivo **o como ticket**, además de las mismas analíticas de flujo que el widget web. Si la opción `require_flow` del flujo está activada, la tarjeta directa «Iniciar conversación» permanece oculta mientras haya un flujo disponible (vuelve a mostrarse si el flujo no se puede cargar). El motor se comprobó contra el motor web con 40 escenarios de paridad (mismos pasos, variables y eventos); las pantallas se verificaron en un emulador en persa (RTL) e inglés.

## Identificar al usuario

Identifica a los usuarios con sesión iniciada (opcional). `identifierHash` es el HMAC-SHA256 del identificador con el token HMAC del buzón y debe calcularlo **tu backend**, nunca la app:

```kotlin
Hodhod.identify(
    HodhodUser(identifier = "user-42", identifierHash = hmacFromYourBackend, name = "Ali", email = "ali@example.com")
) { result -> /* Result<Unit> */ }

Hodhod.logout() // forget the visitor on this device
```

## Idioma, tema y color

Todos los textos, la dirección (RTL/LTR) y las fechas siguen el idioma elegido; la fuente Vazirmatn va incluida. Opciones:

```kotlin
HodhodConfig(
    baseUrl = "https://hodhod.chat",
    websiteToken = "YOUR_WEBSITE_TOKEN",
    locale = "fa",                       // fa, en, ar, de, es, fr (null = device language)
    darkMode = DarkMode.AUTO,            // AUTO, LIGHT, DARK
    accentColorOverride = 0xFF7A4FD1,    // null = inbox widget colour
)
```

## Textos (seis idiomas)

Todos los textos provienen de los archivos de idioma del widget web y se generan como recursos de Android para los seis idiomas (los demás usan inglés):

```bash
python3 tools/gen_strings.py            # regenerate hodhod-ui/src/main/res/values*/hodhod_strings.xml
./gradlew :hodhod-ui:testDebugUnitTest  # fails if a locale misses a key
```

## App de ejemplo

El módulo `sample` configura el SDK desde un formulario (URL base, token del sitio, idioma, tema), identifica a un usuario y abre el chat:

```bash
./gradlew :sample:installDebug          # emulator: base URL http://10.0.2.2:3000
```

## Otros idiomas

[English](README.md) · [فارسی](README.fa.md) · [العربية](README.ar.md) · [Français](README.fr.md) · [Deutsch](README.de.md)
