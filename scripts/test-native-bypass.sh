#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TASK_TEMP=$(mktemp -d)
trap 'rm -rf "$TASK_TEMP"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror \
    -I "$TASK_ROOT/third_party/strongswan/src/frontends/android/app/src/main/jni/libandroidbridge/backend" \
    "$TASK_ROOT/app/src/test/native/android_tun_update_test.c" -o "$TASK_TEMP/bypass-test"
"$TASK_TEMP/bypass-test"
