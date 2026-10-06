<div dir="rtl">

# حزمة هدهد لأندرويد (SDK)

دردشة دعم العملاء الأصلية (Kotlin وJetpack Compose) لأندرويد: دردشة مباشرة، تذاكر، نموذج ما قبل الدردشة، استطلاع الرضا، مرفقات، وضع داكن وست لغات (فارسی، English، العربية، Deutsch، Español، Français) مع دعم كامل للكتابة من اليمين إلى اليسار. تستخدم واجهة الأداة العامة نفسها لموقع هدهد.

## المتطلبات

أندرويد 7.0 فأعلى (minSdk 24)، Kotlin 2، Jetpack Compose، جافا 17. يجب أن يكون الخادم هدهد (HTTPS؛ القيمة `allowCleartext = true` للتطوير المحلي فقط).

## التثبيت

أضف وحدة الواجهة (وهي تجلب وحدة النواة تلقائيًا):

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

الشيفرة المصدرية: <https://github.com/HodHodChat/hodhod-android-sdk>. تبني [JitPack](https://jitpack.io/#HodHodChat/hodhod-android-sdk) الحزمة من وسم git ‏`1.0.0-beta01` (يجب أن يكون الوسم موجودًا في المستودع؛ راجع `docs/RELEASING.md`). وحدة النواة وحدها: `com.github.HodHodChat.hodhod-android-sdk:hodhod-core:1.0.0-beta01`.

## الإعداد

اضبط الإعداد مرة واحدة، مثلًا في `Application.onCreate` (لا يتم أي اتصال بالشبكة قبل استخدام الدردشة):

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hodhod.configure(this, HodhodConfig(baseUrl = "https://hodhod.chat", websiteToken = "YOUR_WEBSITE_TOKEN"))
    }
}
```

## فتح الدردشة

```kotlin
Hodhod.open(context)            // full-screen HodhodChatActivity (deep link: hodhod://chat)
```

## التضمين في Compose

أو ضمّن الدردشة أو زر الإطلاق العائم في واجهة Compose الخاصة بك. تتحدّث شارة الرسائل غير المقروءة أثناء فتح التطبيق:

```kotlin
@Composable
fun SupportScreen(onClose: () -> Unit) = HodhodChat(Modifier.fillMaxSize(), onClose = onClose)

@Composable
fun Home() = Box(Modifier.fillMaxSize()) {
    // ... your content ...
    HodhodBubble(Modifier.align(Alignment.BottomEnd).padding(16.dp)) // unread badge included
}
```

## روبوتات المحادثة (التدفقات)

إذا كان لصندوق الوارد [تدفق روبوت محادثة](https://hodhod.chat/features/chatbot-flows) مفعّل، فإن المكتبة تشغّله محليًا في شاشة البداية دون أي شيفرة إضافية: جميع أنواع العقد الـ17، والمتغيرات والشروط، والتحقق من الإدخال، والتقييم، والتحويل إلى موظف مباشر **أو كتذكرة**، مع نفس تحليلات التدفق التي يسجّلها ودجت الويب. إذا كان خيار `require_flow` مفعّلًا في التدفق، تُخفى بطاقة «بدء المحادثة» المباشرة ما دام التدفق متاحًا (وتعود إذا تعذّر تحميله). تم فحص المحرك مقابل محرك الويب بـ40 سيناريو تكافؤ (نفس الخطوات والمتغيرات والأحداث)، وجرى التحقق من الشاشات على محاكي بالفارسية (من اليمين إلى اليسار) والإنجليزية.

## تعريف المستخدم

عرّف المستخدمين المسجّلين (اختياري). `identifierHash` هو HMAC-SHA256 للمعرّف باستخدام رمز HMAC الخاص بالصندوق، ويجب أن يحسبه **خادمك** وليس التطبيق:

```kotlin
Hodhod.identify(
    HodhodUser(identifier = "user-42", identifierHash = hmacFromYourBackend, name = "Ali", email = "ali@example.com")
) { result -> /* Result<Unit> */ }

Hodhod.logout() // forget the visitor on this device
```

## اللغة والمظهر واللون

تتبع جميع النصوص والاتجاه (RTL/LTR) والتواريخ اللغة المختارة؛ خط Vazirmatn مرفق. الخيارات:

```kotlin
HodhodConfig(
    baseUrl = "https://hodhod.chat",
    websiteToken = "YOUR_WEBSITE_TOKEN",
    locale = "fa",                       // fa, en, ar, de, es, fr (null = device language)
    darkMode = DarkMode.AUTO,            // AUTO, LIGHT, DARK
    accentColorOverride = 0xFF7A4FD1,    // null = inbox widget colour
)
```

## النصوص (ست لغات)

تُولَّد جميع النصوص من ملفات لغات أداة الويب إلى موارد أندرويد للغات الست (وتعود اللغات الأخرى إلى الإنجليزية):

```bash
python3 tools/gen_strings.py            # regenerate hodhod-ui/src/main/res/values*/hodhod_strings.xml
./gradlew :hodhod-ui:testDebugUnitTest  # fails if a locale misses a key
```

## التطبيق التجريبي

تضبط وحدة `sample` الحزمة من نموذج (عنوان الخادم، رمز الموقع، اللغة، المظهر) وتعرّف مستخدمًا وتفتح الدردشة:

```bash
./gradlew :sample:installDebug          # emulator: base URL http://10.0.2.2:3000
```

## لغات أخرى

[English](README.md) · [فارسی](README.fa.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md)

</div>
