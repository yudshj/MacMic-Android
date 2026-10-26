#!/usr/bin/env bash
# One-shot build of the MacMic phone Mic server APK without Gradle/AGP.
#   ./build.sh                 debug-signed build (native lib cached) -> build/MacMicServer.apk
#   ./build.sh install         debug build + install + grant permissions + start service
#   ./build.sh release         release-signed build -> build/MacMicServer-release.apk
#   ./build.sh release-install release build + (re)install + grant permissions + start service
#   ./build.sh clean           remove build/ (keeps keystore + libopus source)
#
# Release signing reads ~/.android/macmic-release.properties (storeFile/storePassword/
# keyAlias/keyPassword). Override paths with MACMIC_RELEASE_KS / MACMIC_RELEASE_PROPERTIES.
# A release-signed APK has a different signature than the debug one, so installing over a
# debug install requires uninstalling the app first (loses runtime permission grants).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(pwd)

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.0.0
PLATFORM_JAR=$SDK/platforms/android-36/android.jar
NDK=${ANDROID_NDK:-$SDK/ndk/29.0.14206865}
CMAKE=$SDK/cmake/3.22.1/bin/cmake
OPUS_VER=1.5.2
OPUS_SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
ABI=arm64-v8a
API=24
PKG=com.macmic.micserver
OUT=$ROOT/build

if [[ "${1:-}" == clean ]]; then rm -rf "$OUT"; exit 0; fi
mkdir -p "$OUT"

# ---------- 0. variant ----------
VARIANT=debug
INSTALL=0
case "${1:-}" in
  ""|debug)         ;;
  install)          INSTALL=1 ;;
  release)          VARIANT=release ;;
  release-install)  VARIANT=release; INSTALL=1 ;;
  *) echo "usage: $0 [debug|install|release|release-install|clean]" >&2; exit 2 ;;
esac
DEBUG_APK=$OUT/MacMicServer.apk
RELEASE_APK=$OUT/MacMicServer-release.apk
RELEASE_PROPS=${MACMIC_RELEASE_PROPERTIES:-$HOME/.android/macmic-release.properties}
APK=$DEBUG_APK

# ---------- 1. native: libopus (static) + JNI wrapper -> libmicserver.so ----------
SO=$OUT/lib/$ABI/libmicserver.so
if [[ ! -f $SO || native/opus_jni.c -nt $SO ]]; then
  cd native
  if [[ ! -d opus-$OPUS_VER ]]; then
    [[ -f opus-$OPUS_VER.tar.gz ]] || curl -fsSL -o opus-$OPUS_VER.tar.gz \
      https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz
    echo "$OPUS_SHA256  opus-$OPUS_VER.tar.gz" | shasum -a 256 -c -
    tar xzf opus-$OPUS_VER.tar.gz
  fi
  cd "$ROOT"
  OB=$OUT/opus-build
  if [[ ! -f $OB/libopus.a ]]; then
    "$CMAKE" -S native/opus-$OPUS_VER -B "$OB" -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
      -DOPUS_BUILD_SHARED_LIBRARY=OFF -DOPUS_BUILD_TESTING=OFF -DOPUS_BUILD_PROGRAMS=OFF \
      -DCMAKE_POSITION_INDEPENDENT_CODE=ON >/dev/null
    "$CMAKE" --build "$OB" >/dev/null
  fi
  CC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android$API-clang
  mkdir -p "$(dirname "$SO")"
  "$CC" -shared -fPIC -O2 -Wall -Inative/opus-$OPUS_VER/include native/opus_jni.c \
    "$OB/libopus.a" -lm -llog -Wl,-z,max-page-size=16384 -Wl,--gc-sections -s -o "$SO"
  echo "native: $SO ($(wc -c <"$SO") bytes)"
fi

# ---------- 2. resources ----------
rm -rf "$OUT/res.zip" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
# Third-party license text must travel with the binary (libopus is BSD-3, statically linked).
mkdir -p "$OUT/assets/licenses"
cp licenses/opus-COPYING.txt "$OUT/assets/licenses/opus.txt"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM_JAR" --manifest AndroidManifest.xml \
  -A "$OUT/assets" --java "$OUT/gen" --auto-add-overlay "$OUT/res.zip"

# ---------- 3. java -> dex ----------
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -nowarn -Xlint:-options -classpath "$PLATFORM_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
"$BT/d8" --release --min-api $API --lib "$PLATFORM_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

# ---------- 4. package, align, sign ----------
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
(cd "$OUT" && zip -q -0 -r unsigned.apk lib)   # store .so uncompressed (extractNativeLibs=false)
"$BT/zipalign" -f -P 16 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [[ "$VARIANT" == "release" ]]; then
  KS=${MACMIC_RELEASE_KS:-$HOME/.android/macmic-release.jks}
  STORE_PASS=""; KEY_PASS=""; KEY_ALIAS=""
  if [[ -f "$RELEASE_PROPS" ]]; then
    STORE_PASS=$(sed -n 's/^storePassword=//p' "$RELEASE_PROPS")
    KEY_PASS=$(sed -n 's/^keyPassword=//p' "$RELEASE_PROPS")
    KEY_ALIAS=$(sed -n 's/^keyAlias=//p' "$RELEASE_PROPS")
    PROP_KS=$(sed -n 's/^storeFile=//p' "$RELEASE_PROPS")
    [[ -n "$PROP_KS" && -f "$PROP_KS" ]] && KS=$PROP_KS
  fi
  [[ -f "$KS" ]] || { echo "missing release keystore: $KS" >&2; exit 1; }
  [[ -n "$STORE_PASS" ]] || { echo "missing storePassword in $RELEASE_PROPS" >&2; exit 1; }
  : "${KEY_ALIAS:=macmic}"
  "$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:${KEY_PASS:-$STORE_PASS}" --ks-key-alias "$KEY_ALIAS" \
    --out "$RELEASE_APK" "$OUT/aligned.apk"
  "$BT/apksigner" verify --print-certs "$RELEASE_APK" | head -3
  APK=$RELEASE_APK
else
  KS=$ROOT/debug.keystore
  if [[ ! -f $KS ]]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias macmic \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MacMic Debug" >/dev/null 2>&1
  fi
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias macmic --out "$DEBUG_APK" "$OUT/aligned.apk"
  "$BT/apksigner" verify "$DEBUG_APK"
  APK=$DEBUG_APK
fi
echo "APK: $APK ($VARIANT)"

if [[ $INSTALL == 1 ]]; then
  ADB="adb ${ANDROID_SERIAL:+-s $ANDROID_SERIAL}"
  if [[ "$VARIANT" == "release" ]]; then
    # Signature differs from the debug install; in-place update is impossible.
    $ADB uninstall "$PKG" >/dev/null 2>&1 || true
  fi
  $ADB install -r "$APK"
  $ADB shell pm grant $PKG android.permission.RECORD_AUDIO
  $ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
  $ADB shell "am start -n $PKG/.MainActivity --ez autostart true" >/dev/null 2>&1 || true
  echo "installed $PKG ($VARIANT)"
fi
