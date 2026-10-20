#!/usr/bin/env bash
# Build this app from a fresh clone.
#
# Requires: JDK 21, Android SDK with NDK 29.0.14206865 and CMake 3.22.1,
#           Docker (libmpv/FFmpeg are compiled from pinned sources), git, python3.
#
# Usage:
#   ./other/tools/build-apk.sh              # arm64 TV + mobile release APKs
#   ABI=armeabi-v7a ./other/tools/build-apk.sh
#
# Optional environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK path (otherwise read from local.properties)
#   SIGN_STORE / SIGN_ALIAS / SIGN_STORE_PASSWORD / SIGN_KEY_PASSWORD
#                                    custom release key; defaults to the local debug keystore
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
ABI=${ABI:-arm64-v8a}
case "$ABI" in
    arm64-v8a) FLAVOR=Arm64_v8a ;;
    armeabi-v7a) FLAVOR=Armeabi_v7a ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac
NDK_VERSION=29.0.14206865
MEDIA3_REV=c8a183b8ca7e57f43213c55f418d89fe45965db8
MEDIA3_MODULES=":lib-common:publishToMavenLocal :lib-ui-danmaku:publishToMavenLocal :lib-ui:publishToMavenLocal"

fail() { echo "ERROR: $*" >&2; exit 1; }

evidence_sdk() {
    if [[ -n "${ANDROID_HOME:-}" ]]; then echo "$ANDROID_HOME"; return; fi
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then echo "$ANDROID_SDK_ROOT"; return; fi
    if [[ -f local.properties ]]; then
        local value
        value=$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)
        if [[ -n "$value" ]]; then echo "${value//\\//}"; return; fi
    fi
}

SDK=$(evidence_sdk || true)
[[ -n "$SDK" && -d "$SDK" ]] || fail "Set ANDROID_HOME to an Android SDK (compileSdk 37, NDK $NDK_VERSION)."
[[ -d "$SDK/ndk/$NDK_VERSION" ]] || fail "Install NDK $NDK_VERSION: sdkmanager 'ndk;$NDK_VERSION' 'cmake;3.22.1'"
command -v docker >/dev/null || fail "Docker is required to build libmpv/FFmpeg from source."
command -v java >/dev/null || fail "JDK 21 is required."
python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 8) else 1)' || fail "Python 3.8+ is required."

# airplay's ffmpeg/libplist live in submodules; a plain clone leaves them empty.
if [[ -d .git ]] && [[ -f .gitmodules ]]; then
    echo "==> Ensuring git submodules"
    git submodule update --init --recursive
fi

# local.properties is intentionally untracked. Generate a working one when absent so a fresh
# clone can build; replace the signing entries with your release key for distribution.
if [[ ! -f local.properties ]]; then
    KEYSTORE=${SIGN_STORE:-$HOME/.android/debug.keystore}
    if [[ ! -f "$KEYSTORE" ]]; then
        echo "==> Generating a local debug signing key"
        mkdir -p "$(dirname "$KEYSTORE")"
        keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storepass android -keypass android \
            -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
            -keyalg RSA -keysize 2048 -validity 10000
    fi
    {
        echo "sdk.dir=$SDK"
        echo "storeFile=$KEYSTORE"
        echo "keyAlias=${SIGN_ALIAS:-androiddebugkey}"
        echo "storePassword=${SIGN_STORE_PASSWORD:-android}"
        echo "keyPassword=${SIGN_KEY_PASSWORD:-android}"
    } > local.properties
    echo "==> Wrote local.properties (debug signing; replace for a distributable APK)"
fi

# The app uses a pinned FongMi/media fork (custom PlayerView API). CI publishes it to mavenLocal;
# reproduce that here when the artifacts are missing.
MEDIA3_DIR="$HOME/.m2/repository/androidx/media3"
if [[ ! -f "$MEDIA3_DIR/media3-ui/1.10.1/media3-ui-1.10.1.aar" ]]; then
    echo "==> Publishing pinned Media3 fork to mavenLocal"
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    git clone --filter=blob:none https://github.com/FongMi/media.git "$TMP/media"
    git -C "$TMP/media" checkout --detach "$MEDIA3_REV"
    echo "sdk.dir=$SDK" > "$TMP/media/local.properties"
    # The fork references the Brotli decoder AAR that AndroidX resolves transitively.
    if ! grep -q '"org.brotli:dec",' "$TMP/media/missing_aar_type_workaround.gradle"; then
        sed -i '/"org.mockito:mockito-core",/a\        "org.brotli:dec",' \
            "$TMP/media/missing_aar_type_workaround.gradle"
    fi
    rm -rf "$MEDIA3_DIR"
    "$TMP/media/gradlew" -p "$TMP/media" $MEDIA3_MODULES --stacktrace
    touch "$HOME/.m2/repository/.media3-${MEDIA3_REV:0:8}.stamp"
fi

echo "==> Building $ABI APKs"
export FONGMI_NATIVE_ABIS="$ABI"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
./gradlew \
    ":app:assembleLeanback${FLAVOR}Release" \
    ":app:assembleMobile${FLAVOR}Release" \
    --no-daemon --stacktrace

echo "Done:"
echo "  app/build/outputs/apk/leanback${FLAVOR}/release/"
echo "  app/build/outputs/apk/mobile${FLAVOR}/release/"
