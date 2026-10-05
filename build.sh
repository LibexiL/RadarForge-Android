#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Builds RadarForge for Android into  build/RadarForge-Android-<version>.apk
#
#  Needs only Java 17+ (and curl, unzip, zip). The Android build tools are
#  downloaded once from GitHub into .tools/ and checked against fixed SHA-256
#  fingerprints, so no Android Studio or SDK install is required.
#
#  Usage:  bash build.sh            build the APK
#          bash build.sh test       run the radar-decoder tests on the JVM
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
ROOT="$PWD"
TOOLS="$ROOT/.tools"
OUT="$ROOT/build"

VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/AndroidManifest.xml | head -1)"
MIN_SDK=26
TARGET_SDK=34

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }
command -v java >/dev/null || die "Java 17 or newer is needed (e.g. sudo dnf install java-21-openjdk)"

# ---- tools: file name | url | sha256 --------------------------------------------
TOOL_LIST=(
  "kotlin-compiler-2.1.21.zip|https://github.com/JetBrains/kotlin/releases/download/v2.1.21/kotlin-compiler-2.1.21.zip|1ba08a8b45da99339a0601134cc037b54cf85e9bc0edbe76dcbd27c2d684a977"
  "android.jar|https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar|6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad"
  "apktool_2.12.1.jar|https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar|66cf4524a4a45a7f56567d08b2c9b6ec237bcdd78cee69fd4a59c8a0243aeafa"
  "jadx-1.5.3.zip|https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip|8280f3799c0273fe797a2bcd90258c943e451fd195f13d05400de5e6451d15ec"
  "uber-apk-signer-1.3.0.jar|https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar|e1299fd6fcf4da527dd53735b56127e8ea922a321128123b9c32d619bba1d835"
)

fetch_tools() {
  mkdir -p "$TOOLS"
  for entry in "${TOOL_LIST[@]}"; do
    IFS='|' read -r name url sum <<<"$entry"
    if [ ! -f "$TOOLS/$name" ] || ! echo "$sum  $TOOLS/$name" | sha256sum -c --status; then
      say "Downloading $name"
      curl -fL --retry 3 -o "$TOOLS/$name.part" "$url" || die "could not download $url"
      echo "$sum  $TOOLS/$name.part" | sha256sum -c --status || die "$name does not match its expected fingerprint"
      mv "$TOOLS/$name.part" "$TOOLS/$name"
    fi
  done
  [ -x "$TOOLS/kotlinc/bin/kotlinc" ] || (cd "$TOOLS" && rm -rf kotlinc && unzip -q kotlin-compiler-2.1.21.zip)
  if [ ! -x "$TOOLS/bin/aapt2" ]; then
    mkdir -p "$TOOLS/bin"
    unzip -q -o -j "$TOOLS/apktool_2.12.1.jar" prebuilt/linux/aapt2_64 -d "$TOOLS/bin"
    mv "$TOOLS/bin/aapt2_64" "$TOOLS/bin/aapt2" && chmod +x "$TOOLS/bin/aapt2"
  fi
  [ -f "$TOOLS/jadx/lib/jadx-1.5.3-all.jar" ] || (cd "$TOOLS" && rm -rf jadx && unzip -q jadx-1.5.3.zip -d jadx)
}

KOTLINC() { "$TOOLS/kotlinc/bin/kotlinc" "$@"; }
STDLIB="$TOOLS/kotlinc/lib/kotlin-stdlib.jar"

run_tests() {
  fetch_tools
  say "Compiling the radar core and tests for the JVM"
  rm -rf "$OUT/test" && mkdir -p "$OUT/test"
  # the core, plus DataManager with stand-ins for the few Android classes it uses (tests/stubs)
  KOTLINC app/src/com/libexil/radarforge/core app/src/com/libexil/radarforge/data/DataManager.kt \
      app/src/com/libexil/radarforge/data/ChunkTracker.kt tests -d "$OUT/test/tests.jar" -jvm-target 17 -nowarn 2>&1 | grep -v '^warning' || true
  [ -f "$OUT/test/tests.jar" ] || die "test build failed"
  say "Running tests"
  java -Xmx2g -cp "$OUT/test/tests.jar:$STDLIB" com.libexil.radarforge.tests.TestsKt "$@"
}

build_apk() {
  fetch_tools
  rm -rf "$OUT/apk" && mkdir -p "$OUT/apk/classes" "$OUT/apk/dex"
  local A="$OUT/apk"

  say "Compiling resources"
  "$TOOLS/bin/aapt2" compile --dir app/res -o "$A/res.zip"
  "$TOOLS/bin/aapt2" link -o "$A/base.apk" -I "$TOOLS/android.jar" --manifest app/AndroidManifest.xml \
      -A app/assets "$A/res.zip" --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
      --auto-add-overlay

  say "Compiling Kotlin ($(find app/src -name '*.kt' | wc -l) files)"
  KOTLINC app/src -cp "$TOOLS/android.jar" -d "$A/classes" -jvm-target 1.8 -nowarn \
      -Xno-call-assertions -Xno-param-assertions -Xno-receiver-assertions 2>&1 | grep -v '^warning' | tee "$A/kotlinc.log" || true
  if grep -q '^error\|: error:' "$A/kotlinc.log"; then die "Kotlin compile failed"; fi
  [ -n "$(find "$A/classes" -name '*.class' | head -1)" ] || die "Kotlin compile produced nothing"

  say "Converting to Android bytecode"
  java -cp "$TOOLS/jadx/lib/jadx-1.5.3-all.jar" com.android.tools.r8.D8 --release --min-api $MIN_SDK \
      --lib "$TOOLS/android.jar" --output "$A/dex" $(find "$A/classes" -name '*.class') "$STDLIB" 2>&1 \
      | grep -v 'JAVA_TOOL_OPTIONS' || true
  [ -f "$A/dex/classes.dex" ] || die "dex conversion failed"

  say "Packaging and signing"
  cp "$A/base.apk" "$A/unsigned.apk"
  (cd "$A/dex" && zip -q -j "$A/unsigned.apk" classes*.dex)
  java -jar "$TOOLS/uber-apk-signer-1.3.0.jar" -a "$A/unsigned.apk" -o "$A/signed" \
      $( [ -f keystore.properties ] && . ./keystore.properties && echo --ks "$KEYSTORE" --ksAlias "$ALIAS" --ksPass "$STORE_PASS" --ksKeyPass "$KEY_PASS" ) \
      2>&1 | grep -E 'success|verified|ERROR|error' | sed 's/^/    /' || true
  local signed
  signed="$(ls "$A"/signed/*.apk 2>/dev/null | head -1)"
  [ -n "$signed" ] || die "signing failed"
  local apk="$OUT/RadarForge-Android-$VERSION_NAME.apk"
  cp "$signed" "$apk"
  say "Done: build/$(basename "$apk") ($(du -h "$apk" | cut -f1))"
}

case "${1:-apk}" in
  test) shift; run_tests "$@" ;;
  tools) fetch_tools ;;
  apk|"") build_apk ;;
  *) die "unknown command: $1 (use: bash build.sh [apk|test])" ;;
esac
