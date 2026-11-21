#!/usr/bin/env bash
# Build the signed release APK, check it ships no debug hooks, and copy it to dist/.
set -euo pipefail
ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
cd "$ROOT"
[ -f keystore/keystore.properties ] || { echo "missing keystore/keystore.properties" >&2; exit 1; }
./gradlew -q :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
A="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify "$APK" 2>/dev/null
[ "$("$A" manifest print "$APK" | grep -cE 'DebugControlReceiver|testOnly|permission\.INTERNET' || true)" = 0 ]
[ "$("$A" files list "$APK" | grep -c browsers.debug.json || true)" = 0 ]
VER=$("$A" manifest version-name "$APK")
mkdir -p dist
cp "$APK" "dist/brainrot-prevention-$VER.apk"
shasum -a 256 "dist/brainrot-prevention-$VER.apk"
