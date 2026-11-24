# ساخت APK نسخه ۰.۹

این پروژه برای Android Gradle Plugin 8.7.3 و Gradle 8.9، Java 17 و Android SDK Platform 35 تنظیم شده است.

## ساخت خودکار در GitHub
۱. محتوای این پوشه را در یک مخزن GitHub بارگذاری کن.
۲. وارد زبانه Actions شو و گردش‌کار «Build CHT AI APK» را اجرا کن (یا روی شاخه main/master پوش کن تا خودکار اجرا شود).
۳. پس از موفقیت، در صفحه اجرای Workflow بخش Artifacts فایل `CHT-AI-debug-apk` را دریافت کن.
۴. فایل ZIP دانلودشده را باز کن؛ `app-debug.apk` داخل آن است.

## ساخت با Android Studio
پروژه را باز کن، اجازه بده Gradle Sync کامل شود و از مسیر Build > Build Bundle(s) / APK(s) > Build APK(s) استفاده کن.
خروجی معمولاً در `app/build/outputs/apk/debug/app-debug.apk` قرار می‌گیرد.

## توجه
APK این پروژه هنوز در محیط سازنده این بسته کامپایل یا روی دستگاه واقعی آزمایش نشده است. APK دیباگ برای نصب آزمایشی است و امضای انتشار (release signing) جداگانه لازم دارد.
