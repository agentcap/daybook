#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -d .tools/android/jdk ]]; then export JAVA_HOME="$PWD/.tools/android/jdk"; fi
if [[ -z "${ANDROID_HOME:-}" && -d .tools/android/sdk ]]; then export ANDROID_HOME="$PWD/.tools/android/sdk"; fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
python3 scripts/speech-dependencies.py
exec ./gradlew --console=plain "$@"
