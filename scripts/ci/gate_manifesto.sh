#!/usr/bin/env bash
# O manifesto pede só o que o app usa, sem backup, e com uma única tela exportada (a de abertura).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
python3 -I - app/src/main/AndroidManifest.xml <<'PY'
import sys, xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
raiz = ET.parse(sys.argv[1]).getroot()
permitidas = {
    "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE", "android.permission.ACCESS_LOCAL_NETWORK",
    "android.permission.RECORD_AUDIO", "android.permission.VIBRATE",
}
pedidas = {p.get(A + "name") for p in raiz.iter("uses-permission")}
extras = pedidas - permitidas
assert not extras, f"permissões a mais: {sorted(extras)}"
app = raiz.find("application")
assert app.get(A + "allowBackup") == "false", "allowBackup tem de ser false"
assert app.get(A + "dataExtractionRules"), "faltam as regras de extração (Android 12+)"
assert app.get(A + "usesCleartextTraffic") != "true", "sem tráfego HTTP em claro liberado"
exportados = [c.get(A + "name") for t in ("activity", "service", "receiver", "provider")
              for c in app.iter(t) if c.get(A + "exported") == "true"]
assert exportados == [".MainActivity"], f"componentes exportados: {exportados}"
PY
grep -q '<exclude domain="root"' app/src/main/res/xml/regras_de_extracao.xml || { echo "FALHA: as regras de extração deviam excluir tudo"; exit 1; }
echo "ok: manifesto"
