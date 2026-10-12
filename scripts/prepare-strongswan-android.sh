#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
STRONGSWAN_DIR="$ROOT_DIR/third_party/strongswan"
OPENSSL_VERSION="3.5.9"
OPENSSL_ARCHIVE="$ROOT_DIR/third_party/openssl-${OPENSSL_VERSION}.tar.gz"
OPENSSL_SOURCE="$ROOT_DIR/third_party/openssl-${OPENSSL_VERSION}-src"
OPENSSL_SHA256="603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
NDK_VERSION="27.3.13750724"

SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
NDK_ROOT=${ANDROID_NDK_ROOT:-}
if [[ -z "$NDK_ROOT" && -n "$SDK_ROOT" ]]; then
    NDK_ROOT="$SDK_ROOT/ndk/$NDK_VERSION"
fi

if [[ -z "$NDK_ROOT" || ! -x "$NDK_ROOT/ndk-build" ]]; then
    echo "Android NDK $NDK_VERSION is required. Set ANDROID_NDK_ROOT or ANDROID_SDK_ROOT." >&2
    exit 1
fi

for tool in autoconf automake libtoolize pkg-config make perl python3; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing native build tool: $tool" >&2
        exit 1
    fi
done

if [[ ! -f "$STRONGSWAN_DIR/configure" ]]; then
    (cd "$STRONGSWAN_DIR" && ./autogen.sh)
fi

if [[ ! -f "$STRONGSWAN_DIR/config.status" ]]; then
    (cd "$STRONGSWAN_DIR" && ./configure)
fi

make -C "$STRONGSWAN_DIR" Android.common.mk >/dev/null

# Git checkouts omit generated C sources (OID tables, parsers and gperf
# output) that the NDK makefiles consume.  The upstream Android build guide
# requires one regular host build to generate them before invoking ndk-build.
if [[ ! -f "$STRONGSWAN_DIR/src/libstrongswan/asn1/oid.c" || \
      ! -f "$STRONGSWAN_DIR/src/libstrongswan/crypto/proposal/proposal_keywords_static.c" ]]; then
    make -C "$STRONGSWAN_DIR" -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '2')" >/dev/null
fi

if [[ ! -f "$OPENSSL_ARCHIVE" ]]; then
    echo "Missing vendored OpenSSL source archive: $OPENSSL_ARCHIVE" >&2
    exit 1
fi
printf '%s  %s\n' "$OPENSSL_SHA256" "$OPENSSL_ARCHIVE" | sha256sum --check --status || {
    echo "OpenSSL source archive checksum mismatch." >&2
    exit 1
}

if [[ ! -f "$OPENSSL_SOURCE/Configure" ]]; then
    mkdir -p "$OPENSSL_SOURCE"
    tar -xzf "$OPENSSL_ARCHIVE" --strip-components=1 -C "$OPENSSL_SOURCE"
fi

OPENSSL_OUT="$STRONGSWAN_DIR/src/frontends/android/app/src/main/jni/openssl"
if [[ ! -f "$OPENSSL_OUT/Android.mk" || \
      ! -f "$OPENSSL_OUT/arm64-v8a/libcrypto.a" || \
      ! -f "$OPENSSL_OUT/armeabi-v7a/libcrypto.a" || \
      ! -f "$OPENSSL_OUT/x86/libcrypto.a" || \
      ! -f "$OPENSSL_OUT/x86_64/libcrypto.a" ]]; then
    NO_DOCKER=1 \
    ANDROID_NDK_ROOT="$NDK_ROOT" \
    OPENSSL_SRC="$OPENSSL_SOURCE" \
    ABIS="arm64-v8a armeabi-v7a x86 x86_64" \
    OUT="$OPENSSL_OUT" \
        "$STRONGSWAN_DIR/src/frontends/android/openssl/build.sh"
fi
