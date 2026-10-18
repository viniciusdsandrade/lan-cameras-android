#!/usr/bin/env bash
# Nada de chave de assinatura, senha, caminho local do SDK ou configuração de nuvem entre os arquivos rastreados.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
achados=$(git ls-files | grep -E '\.(jks|keystore|p12|pem|key)$|(^|/)(local\.properties|google-services\.json|android\.env)$|(^|/)\.idea/' || true)
if [[ -n "$achados" ]]; then
  echo "FALHA: arquivos sensíveis rastreados:"; echo "$achados"; exit 1
fi
if git grep -n -I -E '(storePassword|keyPassword)\s*=\s*"' -- '*.kts' '*.gradle' >/dev/null; then
  echo "FALHA: senha de assinatura escrita no build; ela vem do ambiente (CAMERAS_KEYSTORE_SENHA)"; exit 1
fi
echo "ok: nenhum arquivo sensível rastreado"
