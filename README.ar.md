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
    implementation("com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta04") // brings hodhod-core
}
```

الشيفرة المصدرية: <https://github.com/HodHodChat/hodhod-android-sdk>. تبني [JitPack](https://jitpack.io/#HodHodChat/hodhod-android-sdk) الحزمة من وسم git ‏`1.0.0-beta04` (يجب أن يكون الوسم موجودًا في المستودع؛ راجع `docs/RELEASING.md`). وحدة النواة وحدها: `com.github.HodHodChat.hodhod-android-sdk:hodhod-core:1.0.0-beta04`.

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

## تذاكري

يستطيع الزائر دائمًا رؤية **جميع** تذاكره، المفتوحة والمغلقة، مهما كان وضع الاتصال في الصندوق (محادثة، تذكرة، كلاهما، تذكرة خارج ساعات العمل). تعرض الشاشة الرئيسية صفًّا مضغوطًا «تذاكري» مع عدّاد التذاكر المفتوحة عندما يملك الزائر تذكرة واحدة على الأقل، حتى أثناء محادثة مباشرة. تحتوي القائمة على مرشحات «مفتوحة / مغلقة / الكل» مع الأعداد، وحالات فارغة لكل مرشح، وشارات الحالة، والأوقات النسبية، والسحب للتحديث وتحميل المزيد. التذاكر التي حوّلها وكيل من محادثة تحمل وسم «من المحادثة»، وإذا كانت هي المحادثة الحالية فإن الضغط عليها يفتح شاشة المحادثة. في الصناديق الخاصة بالتذاكر فقط تكون القائمة هي الشاشة الرئيسية مع زر بارز «تذكرة جديدة»؛ وفي صناديق المحادثة فقط يُخفى الزر وتبقى القائمة متاحة. يلزم خادم يدعم `GET /api/v1/widget/tickets?status=&page=&per_page=` و`/tickets/summary`؛ أما الخوادم الأقدم فتُحسب وتُرشَّح على جهة العميل.

## الإعلانات

يمكن لكل صندوق نشر إعلانين كحدّ أقصى (من إعدادات الصندوق في لوحة التحكم). تظهر في أعلى شاشة البداية: الرئيسية، ولوحة التذاكر عندما تؤدي دور الرئيسية، ونموذج ما قبل المحادثة، فوق مشغّل التدفق وبطاقات البدء وصف «تذاكري»؛ وتأتي إشعارات الأعطال بعدها. النوع *إشعار* شريط معلومات أصفر والنوع *تحذير* شريط أحمر مع أيقونة، وكلاهما مقروء في الوضعين الفاتح والداكن. المحتوى نص منسّق (غامق وروابط) وصورة اختيارية. الروابط محصورة في `http` و`https` و`mailto` و`tel` وتُفتح عبر `ACTION_VIEW` (المتصفح أو الطالب أو تطبيق البريد، ولا WebView أبدًا) ولا يُحوَّل النص العادي إلى روابط تلقائيًا. يجب أن تكون الصورة `https` بحدّ أقصى ثابت للارتفاع، ويقرأ TalkBack نصها البديل، ويمكن أن ترتبط برابط، وتُخفى إن فشل تحميلها. للإعلانات القابلة للإغلاق زرّ إغلاق (تسمية TalkBack: «إغلاق»)، ويُحفظ الاختيار على الجهاز لكل `(websiteToken, id, updated_at)` فيعود الإعلان للظهور بعد تعديله.

```kotlin
val items by Hodhod.repository.announcements.collectAsState()   // غير المغلقة، وتتحدّث مع إعدادات الأداة
Hodhod.repository.dismissAnnouncement(items.first().id)           // يُتجاهل للإعلانات غير القابلة للإغلاق
// WidgetConfig.announcements يحتفظ بالقائمة الخام؛ الخوادم القديمة لا ترسل شيئًا (قائمة فارغة)
```

جرّبه بلا خادم: `adb shell am start -n chat.hodhod.sample/.MainActivity --es token x --es fake announcements`.

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
