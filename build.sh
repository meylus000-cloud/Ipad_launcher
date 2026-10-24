#!/usr/bin/env bash
# Збирає підписаний APK без Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Потрібні: JDK 8+ та Android SDK (змінна ANDROID_HOME). На GitHub Actions усе вже є.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "ANDROID_HOME не заданий або не існує" >&2
  exit 1
fi

BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
JAR="$PLATFORM/android.jar"
echo "build-tools: $BT"
echo "platform:    $PLATFORM"

rm -rf build
mkdir -p build/classes build/dex

# 1. Java -> .class (код використовує лише API рівня 14-15)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$JAR" -d build/classes src/com/lite/ipadlauncher/*.java

# 2. .class -> classes.dex (minSdk 14 = Android 4.0)
"$BT/d8" --release --min-api 14 --lib "$JAR" --output build/dex \
  $(find build/classes -name '*.class')

# 3. Маніфест -> базовий APK
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$JAR" \
  --min-sdk-version 14 --target-sdk-version 15 --version-code 1 --version-name 1.0

# 4. Додаємо код у APK
(cd build/dex && zip -q -j ../base.apk classes.dex)

# 5. Вирівнювання та підпис (v1-підпис обов'язковий для Android 4.0)
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
keytool -genkeypair -keystore build/debug.jks -storepass android -keypass android \
  -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=iPad Launcher" 2>/dev/null
"$BT/apksigner" sign --ks build/debug.jks --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 14 --out build/iPadLauncher.apk build/aligned.apk

# 6. Перевірка
"$BT/apksigner" verify --min-sdk-version 14 --verbose build/iPadLauncher.apk | head -5
"$BT/aapt2" dump badging build/iPadLauncher.apk | grep -E "package:|sdkVersion|targetSdkVersion|launchable"
ls -l build/iPadLauncher.apk
echo "Готово: build/iPadLauncher.apk"
