#!/usr/bin/env bash
# Cria, uma vez só, a chave que assina o app instalado no celular: ~/.config/lan-cameras/android.jks e
# ~/.config/lan-cameras/android.env (0600), fora do repositório. A senha é sorteada e nunca é impressa.
# Guarde os dois arquivos: sem eles, uma versão nova só entra no celular depois de desinstalar a antiga.
set -euo pipefail
PASTA="$HOME/.config/lan-cameras"
JKS="$PASTA/android.jks"
ENV="$PASTA/android.env"

if [[ -f "$JKS" || -f "$ENV" ]]; then
  echo "já existe ($JKS ou $ENV): nada a fazer"
  exit 0
fi

if [[ -z "${JAVA_HOME:-}" && -d "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

mkdir -p "$PASTA"
chmod 700 "$PASTA"
umask 077
CAMERAS_KEYSTORE_SENHA="$(openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-32)"
export CAMERAS_KEYSTORE_SENHA
# a senha vai pelo ambiente (-storepass:env), nunca na linha de comando
"$KEYTOOL" -genkeypair -keystore "$JKS" -storetype PKCS12 -alias cameras -keyalg RSA -keysize 3072 \
  -validity 10000 -dname "CN=Cameras de Casa" \
  -storepass:env CAMERAS_KEYSTORE_SENHA -keypass:env CAMERAS_KEYSTORE_SENHA >/dev/null 2>&1
{
  echo "CAMERAS_KEYSTORE=$JKS"
  echo "CAMERAS_KEYSTORE_ALIAS=cameras"
  echo "CAMERAS_KEYSTORE_SENHA=$CAMERAS_KEYSTORE_SENHA"
} >"$ENV"
chmod 600 "$JKS" "$ENV"
echo "ok: chave criada em $JKS (senha em $ENV, 0600)"
