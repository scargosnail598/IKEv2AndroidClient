#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TASK_NATIVE="$TASK_ROOT/third_party/strongswan/src/frontends/android/app/src/main/jni/libandroidbridge/backend"
TASK_TEMP=$(mktemp -d)
trap 'rm -rf "$TASK_TEMP"' EXIT
"${CC:-cc}" -std=c11 -pthread -Wall -Wextra -Werror \
    -I "$TASK_NATIVE" "$TASK_NATIVE/android_traffic.c" \
    "$TASK_ROOT/app/src/test/native/android_traffic_test.c" \
    -o "$TASK_TEMP/android-traffic-test"
"$TASK_TEMP/android-traffic-test"
