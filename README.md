# ARIA - دستیار هوشمند فارسی

<div dir="rtl">

## معرفی

آریا یک دستیار صوتی هوشمند فارسی است که با قابلیت‌های Agent پیشرفته، می‌تواند به اینترنت دسترسی داشته باشد و اطلاعات را از منابع مختلف جمع‌آوری کند.

## ویژگی‌های اصلی

### 🎤 تشخیص گفتار فارسی
- پشتیبانی کامل از زبان فارسی
- تشخیص آنی و دقیق
- قابلیت فعال‌سازی با لمس دایره شناور

### 🤖 هوش مصنوعی قدرتمند
- استفاده از Gemini 2.0 Flash
- پاسخ‌های طبیعی و محاوره‌ای
- حافظه مکالمه

### 🛠 Agent Tools (جدید!)

آریا حالا می‌تواند از ابزارهای مختلف برای جمع‌آوری اطلاعات استفاده کند:

#### ابزارهای وب
- **Web Search**: جستجوی اینترنت با DuckDuckGo
- **Web Browse**: خواندن محتوای صفحات وب

#### ابزارهای کد و توسعه
- **GitHub Search**: جستجوی مخازن GitHub
- **GitHub File Reader**: خواندن فایل‌ها از مخازن
- **GitHub Issues**: مشاهده Issues

#### ابزارهای رسانه
- **YouTube Search**: جستجوی ویدیوها
- **YouTube Transcript**: دریافت متن ویدیوها (در حال توسعه)

#### شبکه‌های اجتماعی
- **Reddit**: جستجوی پست‌ها و نظرات
- **Twitter/X**: جستجوی توییت‌ها (نیاز به احراز هویت)
- **Facebook**: جستجوی پست‌ها (نیاز به احراز هویت)
- **Instagram**: جستجوی پست‌ها (نیاز به احراز هویت)
- **LinkedIn**: جستجوی محتوا (نیاز به احراز هویت)
- **Bilibili**: جستجوی ویدیوها
- **Xiaohongshu**: جستجوی محتوا (نیاز به احراز هویت)

#### ابزارهای دیگر
- **RSS Reader**: خواندن فیدهای RSS/Atom

### 🔍 Tool Router هوشمند

آریا به صورت خودکار بهترین ابزار را برای پاسخ به درخواست شما انتخاب می‌کند:

- "یک ویدیوی آموزش Python پیدا کن" → YouTube Search
- "آخرین خبرها رو از این سایت بخون" → RSS Reader
- "کد این پروژه رو نشون بده" → GitHub
- "درباره هوش مصنوعی جستجو کن" → Web Search

### 🏥 Health Check / Doctor

بررسی سلامت همه ابزارها:
```kotlin
val report = agentBridge.runHealthCheck()
// نمایش وضعیت همه ابزارها، زمان پاسخ، و پیشنهادات
```

### 🔄 Primary + Fallback

اگر یک سرویس در دسترس نباشد، آریا به صورت خودکار از سرویس جایگزین استفاده می‌کند.

## نصب و راه‌اندازی

### پیش‌نیازها
- Android 8.0 (API 26) یا بالاتر
- کلید API از [Google AI Studio](https://makersuite.google.com/app/apikey)

### مراحل نصب

1. **دانلود APK**
   ```bash
   # Build از سورس
   ./gradlew assembleDebug
   ```

2. **نصب برنامه**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. **تنظیمات اولیه**
   - اجرای برنامه
   - ورود کلید Gemini API
   - اعطای مجوزها:
     - نمایش روی برنامه‌های دیگر
     - ضبط صوت
     - اینترنت

## استفاده

### فعال‌سازی آریا
1. دایره آبی رنگ شناور را مشاهده خواهید کرد
2. روی دایره ضربه بزنید
3. صحبت کردن را شروع کنید

### مثال‌های استفاده

```
🗣 "آخرین پروژه‌های React رو تو GitHub پیدا کن"
🤖 *جستجو در GitHub و نمایش نتایج*

🗣 "یک ویدیوی آموزش Kotlin پیدا کن"
🤖 *جستجو در YouTube و نمایش ویدیوها*

🗣 "این لینک رو برام بخون: https://example.com"
🤖 *خواندن محتوای صفحه و خلاصه کردن*

🗣 "آخرین خبرهای تکنولوژی رو بگو"
🤖 *جستجو در وب و ارائه اطلاعات*
```

## معماری

```
ARIA App
├── AriaOverlayService (UI و گفتار)
├── AgentBridge (پل بین AI و Tools)
│   ├── Tool Detection
│   ├── Tool Execution
│   └── Response Generation
└── AgentToolManager
    ├── ToolRegistry (ثبت ابزارها)
    ├── ToolRouter (مسیریابی هوشمند)
    └── Tools
        ├── WebSearchTool
        ├── WebBrowseTool
        ├── GitHubTool
        ├── YouTubeTool
        ├── SocialMediaTools
        └── RSSReaderTool
```

## توسعه

### افزودن ابزار جدید

1. پیاده‌سازی interface `AgentTool`:
```kotlin
class MyNewTool(private val client: OkHttpClient) : AgentTool {
    override val name = "my_tool"
    override val description = "توضیح ابزار"
    override val category = ToolCategory.WEB

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        // پیاده‌سازی منطق ابزار
    }

    override suspend fun healthCheck(): HealthStatus {
        // بررسی سلامت
    }

    override fun getParamsSchema(): JSONObject {
        // تعریف پارامترها
    }
}
```

2. ثبت در `AgentToolManager`:
```kotlin
registry.register(MyNewTool(client))
```

3. افزودن به `ToolRouter` (اختیاری):
```kotlin
containsAny(normalizedRequest, "keyword") ->
    registry.getTool("my_tool")
```

## وابستگی‌ها

- **Kotlin**: 2.0.21
- **Android Gradle Plugin**: 8.7.0
- **OkHttp**: 4.12.0 (درخواست‌های HTTP)
- **JSoup**: 1.17.2 (پردازش HTML/XML)
- **Coroutines**: 1.9.0 (برنامه‌نویسی ناهمزمان)
- **Gemini API**: برای هوش مصنوعی

## مجوزها

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

## امنیت

- کلید API به صورت محلی ذخیره می‌شود
- هیچ داده‌ای به سرورهای شخص ثالث ارسال نمی‌شود
- تمام درخواست‌ها از HTTPS استفاده می‌کنند

## محدودیت‌ها

- برخی شبکه‌های اجتماعی نیاز به احراز هویت دارند
- دریافت transcript از YouTube نیاز به API key دارد
- سرعت پاسخ به کیفیت اینترنت بستگی دارد

## نقشه راه

- [ ] پشتیبانی از YouTube Transcript API
- [ ] احراز هویت برای شبکه‌های اجتماعی
- [ ] کش کردن نتایج
- [ ] پشتیبانی از تصاویر و ویدیو
- [ ] Plugin system برای ابزارهای سفارشی
- [ ] پشتیبانی از چند زبان

## مشارکت

مشارکت‌ها خوش‌آمدند! لطفاً:

1. Fork کنید
2. یک branch جدید بسازید
3. تغییرات را commit کنید
4. Push به branch کنید
5. Pull Request ایجاد کنید

## لایسنس

این پروژه آزاد و متن‌باز است.

## تشکر

- [Agent Reach](https://github.com/example/agent-reach) - الهام از معماری Agent Tools
- Google Gemini API
- جامعه متن‌باز Android

## تماس

برای سوالات و پیشنهادات:
- GitHub Issues: [مشاهده Issues](https://github.com/mrfokolaii-jpg/aria/issues)
- Repository: [ARIA on GitHub](https://github.com/mrfokolaii-jpg/aria)

---

**نسخه**: 0.1.0  
**آخرین به‌روزرسانی**: 2024

</div>
