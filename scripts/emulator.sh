#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$PWD/.tools/android/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.tools/android/sdk}"
export ANDROID_USER_HOME="$PWD/.tools/android/user"
export ANDROID_AVD_HOME="$PWD/.tools/android/avd"
export XDG_RUNTIME_DIR="$PWD/.tools/android/runtime"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
if [[ ! -d "$ANDROID_AVD_HOME/daybook-test.avd" ]]; then
    printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n daybook-test -k 'system-images;android-36;google_apis;x86_64' -d pixel_8
fi
exec "$ANDROID_HOME/emulator/emulator" -avd daybook-test -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -memory 4096 -cores 2 -no-metrics -qemu -cpu host
