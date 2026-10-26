#!/usr/bin/env bash
# Build the design-preview APK from phone-app/design/res + design/reference/*.java into /tmp.
# Never touches phone-app/build or the real app. Does NOT install; prints the adb command.
#   phone-app/design/preview/build_preview.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
DESIGN=$(dirname "$HERE")
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.0.0
PJ=$SDK/platforms/android-36/android.jar
OUT=${OUT:-/tmp/macmic-design-preview}
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir "$DESIGN/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PJ" --manifest "$HERE/AndroidManifest.xml" \
  --custom-package com.macmic.micserver --java "$OUT/gen" "$OUT/res.zip"
javac --release 11 -nowarn -Xlint:-options -classpath "$PJ" -d "$OUT/classes" \
  "$OUT"/gen/com/macmic/micserver/R.java "$DESIGN"/reference/*.java "$HERE"/src/com/macmic/micserver/*.java
"$BT/d8" --release --min-api 29 --lib "$PJ" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -P 16 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS=${KS:-/tmp/macmic-design-preview.keystore}   # outside $OUT so re-builds can `adb install -r`
[[ -f $KS ]] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias preview \
  -keyalg RSA -keysize 2048 -validity 365 -dname "CN=MacMic Design Preview" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/MacMicDesign.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/MacMicDesign.apk"
echo "APK: $OUT/MacMicDesign.apk"
echo "install (optional, separate package):  adb install -r $OUT/MacMicDesign.apk"
echo "open a state:  adb shell am start -n com.macmic.micserver.design/com.macmic.micserver.PreviewActivity --es state streaming"
