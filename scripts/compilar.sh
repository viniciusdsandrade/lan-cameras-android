#!/usr/bin/env bash
# Compila e testa tudo: o núcleo na JVM, os testes de unidade e o lint do app, e o APK de depuração.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# No Mac, sem JAVA_HOME, usa o JDK que vem com o Android Studio.
if [[ -z "${JAVA_HOME:-}" && -d "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
elif [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

./gradlew --no-daemon :nucleo:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug "$@"
echo "ok: app/build/outputs/apk/debug/app-debug.apk"
