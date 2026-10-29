#!/data/data/com.termux/files/usr/bin/bash
# Builds FP Shade without Gradle.
# Requires: JDK 17+, Android SDK platform 34, and aapt2/d8/apksigner/zip on PATH.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
AJ="$SDK/platforms/android-34/android.jar"
OUT=build
APK=fpshade.apk

if [ ! -f "$AJ" ]; then
    echo "android.jar not found at: $AJ"
    echo "Set ANDROID_HOME (or ANDROID_SDK_ROOT) to your Android SDK."
    exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
aapt2 compile --dir res -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
aapt2 link -o "$OUT/base.apk" -I "$AJ" --manifest AndroidManifest.xml \
    -R "$OUT/res.zip" --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 34 --auto-add-overlay

echo "[3/6] javac"
javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

echo "[4/6] d8"
d8 --min-api 26 --lib "$AJ" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

echo "[5/6] package"
cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
(cd "$OUT" && zip -q app-unsigned.apk classes.dex)

# Signing: keystore.properties (release key) if present, else a local debug key.
KS="$PWD/debug.keystore"
KS_PASS=android
KEY_ALIAS=fpshade
KEY_PASS=android
USING_DEBUG=1

if [ -f keystore.properties ]; then
    set -a
    . ./keystore.properties
    set +a
    KS="$PWD/${storeFile:-release.keystore}"
    KS_PASS="${storePassword:-}"
    KEY_ALIAS="${keyAlias:-fpshade}"
    KEY_PASS="${keyPassword:-${storePassword:-}}"
    USING_DEBUG=0
    if [ ! -f "$KS" ]; then
        echo "keystore not found: $KS"
        exit 1
    fi
elif [ ! -f debug.keystore ]; then
    echo "[5/6] creating debug keystore"
    keytool -genkeypair -keystore debug.keystore -alias fpshade \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=FP Shade Debug,O=Local,C=US" >/dev/null 2>&1
fi

echo "[6/6] sign"
if [ "$USING_DEBUG" = "1" ]; then
    echo "WARNING: signed with the debug key - do not distribute this APK."
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$APK" "$OUT/app-unsigned.apk"
apksigner verify --print-certs "$APK" | head -3
ls -l "$APK"
