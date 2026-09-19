#!/usr/bin/env bash
set -euo pipefail

version="0.3.3"
expected_sha256="3bccc9020926b0b7bf1fd09553b41e4678c80cedc5cbe61d90a78fe4b71aca76"
destination="${1:-smarttubetv/libs/libmihomo-android-v${version}.aar}"
url="https://github.com/oviron/libmihomo-android/releases/download/v${version}/libmihomo-android-v${version}.aar"

mkdir -p "$(dirname "$destination")"

if [[ -f "$destination" ]]; then
    actual_sha256="$(sha256sum "$destination" | awk '{print $1}')"
    if [[ "$actual_sha256" == "$expected_sha256" ]]; then
        echo "libmihomo-android v${version} already verified"
        exit 0
    fi
    echo "Existing libmihomo artifact has an invalid checksum" >&2
    exit 1
fi

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
