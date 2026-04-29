#!/usr/bin/env bash
set -euo pipefail

PAUSE=1
if [[ "${1:-}" == "--no-pause" ]]; then
  PAUSE=0
fi

say() {
  printf "\n== %s ==\n" "$1"
}

note() {
  printf "%s\n" "$1"
}

pause() {
  if [[ "$PAUSE" == "1" ]]; then
    read -r -p "Enter para continuar… " _ || true
  fi
}

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "Falta comando requerido: $1" >&2; exit 1; }
}

need oc
need curl
need python3

CTX="$(oc config current-context 2>/dev/null || true)"
if [[ -z "$CTX" ]]; then
  echo "No hay contexto de oc activo. Ejecuta 'oc login'." >&2
  exit 1
fi

APPS_DOMAIN="$(oc get ingresses.config/cluster -o jsonpath='{.spec.domain}' 2>/dev/null || true)"
if [[ -z "$APPS_DOMAIN" ]]; then
  echo "No pude detectar apps domain (ingresses.config/cluster)." >&2
  exit 1
fi

HOST="rhcl-workshop.${APPS_DOMAIN}"
BASE="https://${HOST}"

code() { curl -ksS -o /dev/null -w "%{http_code}" "$@"; }
body1() { curl -ksS "$@" | head -n 1 | tr -d '\n'; }

assert_code() {
  local got expected url
  expected="$1"
  url="$2"
  got="$(code "$url")"
  if [[ "$got" != "$expected" ]]; then
    echo "FAIL: $url -> $got (esperado $expected)" >&2
    exit 1
  fi
  echo "OK:   $url -> $got"
}

say "RHCL Workshop — demo host"
note "Contexto actual: ${CTX}"
note "Demo URL: ${BASE}/"
pause

say "1) Infra owner: HTTPS entrypoint + TLS"
note "Qué decir:"
note "- Tenemos un único hostname público."
note "- TLS termina en el Gateway (TLSPolicy + cert-manager)."
assert_code "200" "${BASE}/"
assert_code "200" "${BASE}/health"
pause

say "2) Platform engineer: Zero-trust por defecto (deny-all en el Gateway)"
note "Qué decir:"
note "- Por defecto, el Gateway bloquea todo."
note "- Sólo lo que el equipo de plataforma / developer habilita por ruta pasa."
hello_nohdr="$(code "${BASE}/hello")"
echo "GET /hello sin credenciales -> ${hello_nohdr} (esperado 401/403)"
pause

say "3) Developer: AuthPolicy por ruta (API key) + RateLimitPolicy por identidad"
note "Qué decir:"
note "- El developer publica una ruta y adjunta políticas a nivel HTTPRoute."
note "- API key para identidad + rate limit por usuario."
assert_code "200" "${BASE}/health"
assert_code "200" "${BASE}/hello" -H 'Authorization: APIKEY IAMALICE'
assert_code "200" "${BASE}/hello" -H 'Authorization: APIKEY IAMBOB'

note ""
note "Burst (12 req) para mostrar 429:"
python3 - <<PY
import subprocess, time
base="${BASE}"
codes=[]
for i in range(12):
  r=subprocess.run(["curl","-ksS","-o","/dev/null","-w","%{http_code}",f"{base}/hello","-H","Authorization: APIKEY IAMALICE"], capture_output=True, text=True)
  codes.append(r.stdout.strip())
  time.sleep(1)
from collections import Counter
print("codes:", Counter(codes))
PY
pause

say "4) Use case: A/B testing y Canary (traffic shaping)"
note "Qué decir:"
note "- El Gateway enruta a múltiples backends por pesos."
note "- Esto habilita A/B y canary sin tocar la app."
echo "A/B /ab -> $(body1 "${BASE}/ab")"
echo "Canary /canary -> $(body1 "${BASE}/canary")"

note ""
note "Muestreo rápido (60/80 requests) para ver distribución:"
python3 - <<PY
import subprocess
from collections import Counter
base="${BASE}"
def sample(path, n):
  out=[]
  for _ in range(n):
    r=subprocess.run(["curl","-ksS",f"{base}{path}"], capture_output=True, text=True)
    out.append(r.stdout.strip())
  return Counter(out)
print("A/B 60:", sample("/ab", 60))
print("Canary 80:", sample("/canary", 80))
PY
pause

say "5) Use case: Consumo de API pública (external) sin CORS"
note "Qué decir:"
note "- El browser llama same-origin a /external."
note "- Un servicio interno proxya un endpoint público y devuelve JSON."
assert_code "200" "${BASE}/external"
note "Primeras líneas del JSON:"
curl -ksS "${BASE}/external" | head -n 12
pause

say "6) Use case: OIDC (Keycloak) + ruta protegida (/secure)"
note "Qué decir:"
note "- Keycloak corre dentro del cluster y se expone por /auth."
note "- /secure está protegida: sin token redirige a login; con token devuelve 200."
assert_code "200" "${BASE}/auth/realms/rhcl/.well-known/openid-configuration"

note ""
note "Sin token, /secure debería redirigir (302):"
curl -ksS -I "${BASE}/secure" | sed -n '1,10p'

note ""
note "Token (password grant) y llamada a /secure:"
resp="$(curl -ksS -X POST "${BASE}/auth/realms/rhcl/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=rhcl-ui' \
  --data-urlencode 'username=demo' \
  --data-urlencode 'password=demo')"

token="$(printf '%s' "$resp" | python3 -c 'import sys,json; print(json.loads(sys.stdin.read()).get("access_token",""))')"
if [[ -z "$token" ]]; then
  echo "FAIL: no pude obtener token. Respuesta:" >&2
  echo "$resp" | head -c 400 >&2
  echo >&2
  exit 1
fi

secure_code="$(code "${BASE}/secure" -H "Authorization: Bearer ${token}")"
echo "GET /secure con Bearer -> ${secure_code}"
curl -ksS "${BASE}/secure" -H "Authorization: Bearer ${token}" | head -n 5

say "Estado final"
note "Si todo llegó hasta aquí, la demo está OK."

