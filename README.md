# PodScribe

**Persian:** پادساین یک اپ اندرویدی برای دنبال‌کردن پادکست‌ها، گوش‌دادن به قسمت‌ها و ساخت یا ترجمه‌ی رونوشت صوتی است. دارای پخش‌کننده‌ی تمام‌صفحه، ذخیره‌ی قسمت‌ها، رابط فارسی و دو تم روشن و تیره است. برای ترنسکریپشن ابری به Gemini API key نیاز دارید؛ مدل روی‌دستگاه نیز در تنظیمات قابل انتخاب است.

**English:** PodScribe is an Android podcast app for following shows, listening to episodes, and creating or translating transcripts. It includes a full-screen player, episode downloads, Persian/RTL support, and light and dark themes. Cloud transcription requires a Gemini API key; an on-device engine is also available in Settings.

## Download / دریافت

Get the Android APK and source archive from the [latest release](../../releases/latest). Requires Android 8.0 (API 26) or later. Enable installation from your browser or file manager when prompted.

## Build / ساخت

Open this project in Android Studio, sync Gradle, and run the `app` configuration on an Android device or emulator. The `app` module uses Android Gradle Plugin 9.0.1 and Kotlin Compose 2.2.20.

## License / مجوز

The bundled sherpa-onnx component includes its Apache 2.0 license at `app/src/main/assets/sherpa-onnx-LICENSE.txt`. No license is specified for the PodScribe project source.
