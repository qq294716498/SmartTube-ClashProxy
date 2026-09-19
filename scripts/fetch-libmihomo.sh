#!/usr/bin/env bash
set -euo pipefail

version="0.3.3"
expected_sha256="3bccc9020926b0b7bf1fd09553b41e4678c80cedc5cbe61d90a78fe4b71aca76"
destination="${1:-smarttubetv/libs/libmihomo-android-v${version}.aar}"
jni_destination="smarttubetv/src/stproxy/jniLibs"
url="https://github.com/oviron/libmihomo-android/releases/download/v${version}/libmihomo-android-v${version}.aar"

mkdir -p "$(dirname "$destination")"

if [[ -f "$destination" ]]; then
    actual_sha256="$(sha256sum "$destination" | awk '{print $1}')"
    if [[ "$actual_sha256" == "$expected_sha256" ]]; then
        echo "libmihomo-android v${version} already verified"
    else
        echo "Existing libmihomo artifact has an invalid checksum" >&2
        exit 1
    fi
else
    temporary="${destination}.download"
    trap 'rm -f "$temporary"' EXIT
    curl --fail --location --retry 3 --output "$temporary" "$url"

    actual_sha256="$(sha256sum "$temporary" | awk '{print $1}')"
    if [[ "$actual_sha256" != "$expected_sha256" ]]; then
        echo "libmihomo checksum mismatch" >&2
        exit 1
    fi

    mv "$temporary" "$destination"
    trap - EXIT
    echo "Downloaded and verified libmihomo-android v${version}"
fi

# SmartTube's AGP/R8 baseline cannot consume the library's Kotlin 2.x bytecode.
# Package only the verified native payload; the stproxy source set contains a
# small Java 8 facade with the exact JNI class and method names.
rm -rf "$jni_destination"
mkdir -p "$jni_destination"
for abi in arm64-v8a armeabi-v7a; do
    mkdir -p "$jni_destination/$abi"
    unzip -j "$destination" "jni/$abi/libclash.so" "jni/$abi/libmihomo-jni.so" \
        -d "$jni_destination/$abi" >/dev/null
done
echo "Extracted verified Mihomo native libraries for arm64-v8a and armeabi-v7a"
