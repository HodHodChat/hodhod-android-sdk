<div dir="rtl">

# کیت توسعهٔ اندروید هدهد

گفت‌وگوی پشتیبانی بومی (Kotlin و Jetpack Compose) برای اندروید: گفت‌وگوی زنده، تیکت، فرم پیش‌چت، نظرسنجی رضایت، پیوست، حالت تیره و شش زبان (فارسی، English، العربية، Deutsch، Español، Français) با پشتیبانی کامل از راست‌به‌چپ. همان API عمومی ویجت وب‌سایت هدهد را به کار می‌برد.

## پیش‌نیازها

اندروید ۷٫۰ به بالا (minSdk 24)، کاتلین ۲، Jetpack Compose، جاوا ۱۷. سرور باید هدهد باشد (HTTPS؛ مقدار `allowCleartext = true` فقط برای توسعهٔ محلی).

## نصب

ماژول رابط کاربری را اضافه کنید (ماژول هسته را خودش می‌آورد):

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

کد منبع: <https://github.com/HodHodChat/hodhod-android-sdk>. بسته را [JitPack](https://jitpack.io/#HodHodChat/hodhod-android-sdk) از روی تگ گیت `1.0.0-beta04` می‌سازد (این تگ باید در مخزن وجود داشته باشد؛ `docs/RELEASING.md` را ببینید). فقط ماژول هسته: `com.github.HodHodChat.hodhod-android-sdk:hodhod-core:1.0.0-beta04`.

## پیکربندی

یک بار پیکربندی کنید، مثلاً در `Application.onCreate` (تا وقتی گفت‌وگو استفاده نشود هیچ درخواست شبکه‌ای نمی‌رود):

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hodhod.configure(this, HodhodConfig(baseUrl = "https://hodhod.chat", websiteToken = "YOUR_WEBSITE_TOKEN"))
    }
}
```

## باز کردن گفت‌وگو

```kotlin
Hodhod.open(context)            // full-screen HodhodChatActivity (deep link: hodhod://chat)
```

## قرار دادن در Compose

یا گفت‌وگو یا دکمهٔ شناور را در رابط Compose خودتان بگذارید. نشان پیام‌های خوانده‌نشده تا وقتی برنامه باز است به‌روز می‌شود:

```kotlin
@Composable
fun SupportScreen(onClose: () -> Unit) = HodhodChat(Modifier.fillMaxSize(), onClose = onClose)

@Composable
fun Home() = Box(Modifier.fillMaxSize()) {
    // ... your content ...
    HodhodBubble(Modifier.align(Alignment.BottomEnd).padding(16.dp)) // unread badge included
}
```

## ربات‌های گفت‌وگو (فلو)

اگر صندوق ورودی یک [فلو ربات گفت‌وگو](https://hodhod.chat/features/chatbot-flows) فعال داشته باشد، کیت آن را بدون هیچ کد اضافه‌ای به‌صورت بومی روی صفحهٔ شروع اجرا می‌کند: هر ۱۷ نوع گره، متغیرها و شرط‌ها، اعتبارسنجی ورودی، امتیازدهی و تحویل به اپراتور زنده **یا به‌صورت تیکت**، همراه با همان تحلیل‌های فلو که ویجت وب ثبت می‌کند. اگر گزینهٔ `require_flow` فلو روشن باشد، کارت «شروع گفت‌وگو» تا وقتی فلو در دسترس است پنهان می‌ماند (اگر فلو بارگذاری نشود دوباره نمایش داده می‌شود). موتور فلو با ۴۰ سناریوی هم‌ارزی در برابر موتور وب سنجیده شده است (گام‌ها، متغیرها و رویدادها یکسان) و صفحه‌ها روی شبیه‌ساز در فارسی (راست‌به‌چپ) و انگلیسی بررسی شده‌اند.

## تیکت‌های من

بازدیدکننده همیشه **همهٔ** تیکت‌های خود (باز و بسته) را می‌بیند، فارغ از حالت تماس صندوق (گفت‌وگو، تیکت، هر دو، تیکت در خارج از ساعت کاری). صفحهٔ اصلی تا وقتی دست‌کم یک تیکت وجود دارد، ردیف فشردهٔ «تیکت‌های من» را با نشان تعداد تیکت‌های باز نشان می‌دهد، حتی هنگام گفت‌وگوی زنده. فهرست فیلترهای «باز / بسته / همه» را با شمارنده دارد (پیش‌فرض: «باز» اگر تیکت بازی باشد، وگرنه «همه»)، برای هر فیلتر حالت خالی جدا، نشان وضعیت، زمان نسبی، کشیدن برای تازه‌سازی و بارگذاری صفحه‌های بعدی. تیکت‌هایی که اپراتور از گفت‌وگو ساخته با برچسب «از گفت‌وگو» دیده می‌شوند و اگر همان گفت‌وگوی فعلی باشند، لمس آن صفحهٔ گفت‌وگو را باز می‌کند. در صندوق‌های فقط‌تیکت، فهرست صفحهٔ اصلی است و دکمهٔ برجستهٔ «تیکت جدید» دارد؛ در صندوق‌های فقط‌گفت‌وگو دکمه پنهان است ولی فهرست در دسترس می‌ماند. به سروری با `GET /api/v1/widget/tickets?status=&page=&per_page=` و `/tickets/summary` نیاز دارد؛ سرورهای قدیمی‌تر سمت کلاینت شمرده و فیلتر می‌شوند.

برای رابط‌های سفارشی: `repository.ticketSummary` (باز/کل) و `repository.loadTickets(TicketFilter.OPEN, page)`.

## اطلاعیه‌ها

هر صندوق می‌تواند تا دو اطلاعیه منتشر کند (از تنظیمات صندوق در داشبورد). اطلاعیه‌ها بالای صفحهٔ شروع نمایش داده می‌شوند: صفحهٔ خانه، پنل تیکت وقتی نقش صفحهٔ خانه را دارد و فرم پیش از گفت‌وگو؛ بالاتر از اجرای فلو، کارت‌های شروع و ردیف «تیکت‌های من»؛ اعلان‌های اختلال بعد از آن‌ها می‌آیند. نوع *اطلاعیه* نوار زردِ اطلاع‌رسانی و نوع *هشدار* نوار قرمز با آیکون است و هر دو در حالت روشن و تاریک خوانا هستند. محتوا متن غنی (پررنگ و پیوند) و یک تصویر اختیاری است. پیوندها فقط `http`، `https`، `mailto` و `tel` هستند، با `ACTION_VIEW` باز می‌شوند (مرورگر، شماره‌گیر، برنامهٔ ایمیل؛ هرگز WebView) و متن ساده هرگز خودکار به پیوند تبدیل نمی‌شود. تصویر باید `https` باشد، حداکثر ارتفاع ثابت دارد، متن جایگزین برای TalkBack خوانده می‌شود، می‌تواند پیوند داشته باشد و اگر بارگذاری نشود پنهان می‌شود. اطلاعیه‌های قابل‌بستن دکمهٔ بستن دارند (برچسب TalkBack: «بستن»)؛ انتخاب کاربر روی دستگاه و به‌ازای `(websiteToken, id, updated_at)` به خاطر سپرده می‌شود، پس با ویرایش اطلاعیه دوباره نمایش داده می‌شود.

```kotlin
val items by Hodhod.repository.announcements.collectAsState()   // بسته‌نشده‌ها؛ همراه پیکربندی ویجت تازه می‌شود
Hodhod.repository.dismissAnnouncement(items.first().id)           // برای اطلاعیهٔ غیرقابل‌بستن نادیده گرفته می‌شود
// WidgetConfig.announcements فهرست خام را نگه می‌دارد؛ سرورهای قدیمی چیزی نمی‌فرستند (فهرست خالی)
```

بدون سرور امتحان کنید: `adb shell am start -n chat.hodhod.sample/.MainActivity --es token x --es fake announcements`.

## شناسایی کاربر

کاربران واردشده را شناسایی کنید (اختیاری). `identifierHash` همان HMAC-SHA256 شناسه با توکن HMAC صندوق است و باید توسط **سرور شما** محاسبه شود، نه داخل برنامه:

```kotlin
Hodhod.identify(
    HodhodUser(identifier = "user-42", identifierHash = hmacFromYourBackend, name = "Ali", email = "ali@example.com")
) { result -> /* Result<Unit> */ }

Hodhod.logout() // forget the visitor on this device
```

## زبان، پوسته و رنگ

همهٔ متن‌ها، جهت (راست‌به‌چپ/چپ‌به‌راست) و تاریخ‌ها از زبان انتخابی پیروی می‌کنند (تاریخ شمسی برای فارسی)؛ فونت وزیرمتن همراه کتابخانه است. گزینه‌ها:

```kotlin
HodhodConfig(
    baseUrl = "https://hodhod.chat",
    websiteToken = "YOUR_WEBSITE_TOKEN",
    locale = "fa",                       // fa, en, ar, de, es, fr (null = device language)
    darkMode = DarkMode.AUTO,            // AUTO, LIGHT, DARK
    accentColorOverride = 0xFF7A4FD1,    // null = inbox widget colour
)
```

## متن‌ها (شش زبان)

همهٔ متن‌ها از فایل‌های زبان ویجت وب گرفته و برای شش زبان به منابع اندروید تبدیل می‌شوند (زبان‌های دیگر به انگلیسی برمی‌گردند):

```bash
python3 tools/gen_strings.py            # regenerate hodhod-ui/src/main/res/values*/hodhod_strings.xml
./gradlew :hodhod-ui:testDebugUnitTest  # fails if a locale misses a key
```

## حریم خصوصی: پشتیبان‌گیری و کش

* نشست گفت‌وگو (توکن‌ها) در فایل `hodhod_session` (رمزنگاری‌شده با Keystore اندروید) نگه‌داری می‌شود؛ نسخهٔ ساده `hodhod_session_plain` فقط وقتی Keystore کار نکند ساخته می‌شود. کلیدهای Keystore در پشتیبان قرار نمی‌گیرند. کتابخانه نمی‌تواند `android:allowBackup="false"` را اجباری کند (هنگام ادغام مانیفست با برنامهٔ میزبان تداخل ایجاد می‌شود)؛ پس برنامهٔ میزبان یا `android:allowBackup="false"` بگذارد یا قوانین همراه `hodhod-core` را معرفی کند:

```xml
<application
    android:dataExtractionRules="@xml/hodhod_data_extraction_rules"  <!-- Android 12+ -->
    android:fullBackupContent="@xml/hodhod_backup_rules">             <!-- Android 11 and lower -->
```

  اگر برنامه قوانین خودش را دارد، دو خط `<exclude domain="sharedpref" path="hodhod_session.xml" />` و `hodhod_session_plain.xml` را به آن اضافه کنید.
* فایل‌هایی که کاربر انتخاب یا عکس‌برداری می‌کند فقط برای آپلود در کش برنامه کپی می‌شوند و پس از آپلود موفق (و با `Hodhod.logout()`) پاک می‌شوند؛ باقی‌ماندهٔ آپلودهای ناموفق پس از ۲۴ ساعت پاک می‌شود.

## برنامهٔ نمونه

ماژول `sample` کیت را از یک فرم (نشانی سرور، توکن وب‌سایت، زبان، پوسته) پیکربندی می‌کند، کاربر را شناسایی می‌کند و گفت‌وگو را باز می‌کند:

```bash
./gradlew :sample:installDebug          # emulator: base URL http://10.0.2.2:3000
```

## زبان‌های دیگر

[English](README.md) · [العربية](README.ar.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md)

</div>
