---
name: drivecast-tv-test
description: Test DriveCast TV as a launcher on an Android TV emulator or a real box (install, set home, D-pad walk, phone page, crashes, memory) and report with screenshots. Use when asked to test, check or verify DriveCast TV.
---

# اختبار DriveCast TV

كلّم المستخدم بلغته، وبدون مصطلحات.

## الطريقة الأسهل: محاكي في GitHub (بدون أي شي على جهازه)
1. `gh workflow run "TV test" -R mazenDevloper/drivecast-tv` (ويشتغل لحاله بعد كل بناء APK ناجح).
2. انتظر: `gh run watch -R mazenDevloper/drivecast-tv $(gh run list -R mazenDevloper/drivecast-tv -w "TV test" -L1 --json databaseId -q '.[0].databaseId')`
3. نزّل النتيجة: `gh run download <id> -R mazenDevloper/drivecast-tv -n tv-test -D tv-test`
4. اقرأ `tv-test/report.md`، وافتح الصور (01-home … 10-mouse) وتأكد بعينك:
   - إطار التركيز واضح على كل عنصر، والتنقل بالريموت وحده يكفي
   - المحتوى داخل حدود الشاشة (overscan)، والنصوص مقروءة فوق الصورة
   - وضع الصورة (05): اليوم في النص، شريط الصلاة والمباراة تحت
5. بلّغ المستخدم: نجح / فشل، أهم صورتين، وأي خطأ في `errors.txt` أو `crash.txt`.

## على تلفزيون حقيقي أو محاكي على جهازه (adb موصول)
- `adb connect <ip>:5555` للتلفزيون (فعّل «تصحيح USB / الشبكة» من خيارات المطوّر)، أو محاكي Android TV من Android Studio (Device Manager ← TV ← 1080p).
- نزّل آخر نسخة: `gh release download latest -R mazenDevloper/drivecast-tv -p DriveCastTV.apk`
- `bash tools/tv-test.sh DriveCastTV.apk <serial>` — نفس الخطوات، والنتيجة في `tv-test/`.

## ملاحظات
- الريموت فقط: `adb shell input keyevent KEYCODE_DPAD_*` / `KEYCODE_DPAD_CENTER` / `KEYCODE_BACK` / `KEYCODE_HOME` / `KEYCODE_MENU`.
- صفحة الجوال: `adb forward tcp:18080 tcp:8080` ثم `curl http://127.0.0.1:18080/…` (`/status`, `/reciters`, POST `/key`, `/quran`, `/mouse`).
- صوت المحاكي مطفي في GitHub، فـ«ما فيه صوت» هناك تحذير مو فشل.
- لا تمسح بيانات المستخدم ولا تعطّل تطبيقات في اختبار؛ التعطيل له `tools/disable.sh` مع نسخة احتياطية.
