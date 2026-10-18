#!/usr/bin/env bash
# Compila o app assinado com a chave local e instala no celular pelo adb (USB ou depuração por Wi-Fi).
# Com mais de um aparelho conectado, escolha um com ANDROID_SERIAL=… (o serial não fica no repositório).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ENV="$HOME/.config/lan-cameras/android.env"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb

[[ -f "$ENV" ]] || { echo "falta a chave de assinatura: rode scripts/preparar_android_env.sh"; exit 1; }
set -a
# shellcheck disable=SC1090
source "$ENV"
set +a

if [[ -z "${JAVA_HOME:-}" && -d "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

aparelhos=$("$ADB" devices | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d ' ')
if [[ "$aparelhos" -eq 0 ]]; then
  echo "nenhum celular no adb: ligue o cabo USB (com a depuração USB ativada) e aceite a chave no celular"
  exit 1
fi
if [[ "$aparelhos" -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
  echo "há $aparelhos aparelhos no adb: escolha um com ANDROID_SERIAL=… (veja \`adb devices\`)"
  exit 1
fi

./gradlew --no-daemon -q :app:assembleRelease
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
echo "ok: instalado; abra \"Câmeras de Casa\" no celular"
