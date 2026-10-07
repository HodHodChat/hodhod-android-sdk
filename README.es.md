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
    implementation("com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta04") // brings hodhod-core
}
```

Código fuente: <https://github.com/HodHodChat/hodhod-android-sdk>. [JitPack](https://jitpack.io/#HodHodChat/hodhod-android-sdk) compila el paquete a partir de la etiqueta git `1.0.0-beta04` (la etiqueta debe existir en el repositorio; véase `docs/RELEASING.md`). Solo el módulo core: `com.github.HodHodChat.hodhod-android-sdk:hodhod-core:1.0.0-beta04`.

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

## Mis tickets

Los visitantes siempre ven **todos** sus tickets, abiertos y cerrados, sea cual sea el modo de contacto de la bandeja (chat, ticket, ambos, ticket fuera de horario). La pantalla de inicio muestra una fila compacta «Mis tickets» con el número de tickets abiertos cuando hay al menos un ticket, también durante un chat en vivo. La lista tiene los filtros Abiertos / Cerrados / Todos con contadores (por defecto Abiertos si hay alguno, si no Todos), estados vacíos por filtro, insignias de estado, tiempos relativos, tirar para actualizar y cargar más. Los tickets que un agente convirtió desde un chat llevan la etiqueta «Del chat»; si es el chat en vivo actual, al tocarlo se abre el chat. En bandejas solo de tickets la lista es la pantalla de inicio con un botón destacado «Nuevo ticket»; en bandejas solo de chat el botón se oculta pero la lista sigue accesible. Requiere un servidor con `GET /api/v1/widget/tickets?status=&page=&per_page=` y `/tickets/summary`; los servidores antiguos se cuentan y filtran en el cliente.

## Anuncios

Una bandeja puede publicar hasta dos anuncios (en los ajustes de la bandeja del panel). Aparecen en lo más alto de la pantalla de inicio: Home, el panel de tickets cuando hace de Home y el formulario previo al chat, por encima del flujo, las tarjetas de inicio y la fila «Mis tickets»; los avisos de incidencias van después. Un *aviso* es un banner amarillo informativo y una *advertencia* un banner rojo con icono; ambos se leen bien en claro y oscuro. El contenido es texto enriquecido (negrita, enlaces) y una imagen opcional. Los enlaces se limitan a `http`, `https`, `mailto` y `tel`, se abren con `ACTION_VIEW` (navegador, marcador, app de correo; nunca un WebView) y el texto sin formato nunca se convierte en enlace automáticamente. Las imágenes deben ser `https`, tienen una altura máxima fija, TalkBack lee su texto alternativo, pueden llevar un enlace y se ocultan si no cargan. Los anuncios descartables tienen un botón de cierre (etiqueta de TalkBack: «Cerrar»); la elección se recuerda en el dispositivo por `(websiteToken, id, updated_at)`, así que un anuncio editado vuelve a mostrarse.

```kotlin
val items by Hodhod.repository.announcements.collectAsState()   // los no descartados, actualizados con la configuración del widget
Hodhod.repository.dismissAnnouncement(items.first().id)           // se ignora en anuncios no descartables
// WidgetConfig.announcements conserva la lista original; los servidores antiguos no envían nada (lista vacía)
```

Pruébalo sin servidor: `adb shell am start -n chat.hodhod.sample/.MainActivity --es token x --es fake announcements`.

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
