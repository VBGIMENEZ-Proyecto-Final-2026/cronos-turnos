#!/usr/bin/env bash
# SOLO DESARROLLO: simula el recorrido de un cliente (la app) contra cronos-turnos y atlas-catalogo corriendo, y
# comprueba en cada paso que la respuesta sea la esperada. Sirve de prueba cruzada (el token que emite cronos lo
# acepta atlas) y de guion para mostrar el flujo.
#
#   1. registrarse en cronos y repetir el registro      4. consultar su cuenta en cronos
#   2. iniciar sesión (mal y bien) y mirar el token     5. usar el MISMO token en atlas (categorías, profesionales)
#   3. qué ve el cliente sin permiso o con un mal token 6. pedirle a cronos los turnos libres de un profesional
#
# Requisitos: cronos en :8081 y atlas en :8080 (con la clave pública del mismo par), curl, jq, openssl y python3.
#
# Uso:   scripts/recorrido-cliente.sh
# Variables (todas opcionales):
#   CRONOS_URL=http://localhost:8081   ATLAS_URL=http://localhost:8080
#   DEV_PRIVATE_KEY=~/.atlas-dev/dev-private.pem   la clave de DESARROLLO con la que firma cronos: permite armar un
#                                      token vencido y uno con un rol que no alcanza (sin ella se omiten esos pasos)
#   LIMPIAR=1                          borra al final el usuario de prueba de la base de cronos (docker exec cronos-db)
#
# Nada de lo que imprime es secreto: el token se muestra recortado y los claims no llevan datos personales.
set -uo pipefail

CRONOS_URL="${CRONOS_URL:-http://localhost:8081}"
ATLAS_URL="${ATLAS_URL:-http://localhost:8080}"
DEV_PRIVATE_KEY="${DEV_PRIVATE_KEY:-$HOME/.atlas-dev/dev-private.pem}"
LOGIN="cliente.$(date +%s)"
CLAVE="una-clave-123"

for cmd in curl jq openssl python3; do
  command -v "$cmd" >/dev/null || { echo "Falta el comando: $cmd" >&2; exit 2; }
done

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
OK=0; MAL=0; N=0
STATUS=""; BODY=""

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# Arma un JWT RS256 a mano: forjar <clave.pem> <sub> <roles> <segundos-de-vigencia>. Un valor negativo da un token vencido.
forjar() {
  local key="$1" sub="$2" auth="$3" ttl="$4" now exp iat h p s
  now=$(date +%s); exp=$((now + ttl)); iat=$now
  [ "$exp" -le "$now" ] && iat=$((exp - 3600))
  h=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
  p=$(printf '{"sub":"%s","auth":"%s","iat":%s,"exp":%s}' "$sub" "$auth" "$iat" "$exp" | b64url)
  s=$(printf '%s.%s' "$h" "$p" | openssl dgst -sha256 -sign "$key" -binary | b64url)
  printf '%s.%s.%s' "$h" "$p" "$s"
}

# Cambia un carácter del MEDIO de la firma (el último no sirve: tiene bits sin uso y puede no alterar nada).
alterar() { python3 - "$1" <<'PY'
import sys
h, p, s = sys.argv[1].split('.')
m = len(s) // 2
print('.'.join([h, p, s[:m] + ('A' if s[m] != 'A' else 'B') + s[m + 1:]]), end='')
PY
}

titulo() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# pedir METODO URL [args de curl...]  → deja STATUS y BODY
pedir() {
  local metodo="$1" url="$2"; shift 2
  STATUS=$(curl -s -o "$TMP/body" -w '%{http_code}' -X "$metodo" "$url" "$@" 2>/dev/null) || STATUS="000"
  BODY="$(cat "$TMP/body" 2>/dev/null)"
}

# esperar "descripción" <status esperado> [code esperado]
esperar() {
  local desc="$1" status="$2" code="${3:-}" got_code
  N=$((N + 1))
  got_code=$(printf '%s' "$BODY" | jq -r '.code // empty' 2>/dev/null)
  if [ "$STATUS" = "$status" ] && { [ -z "$code" ] || [ "$got_code" = "$code" ]; }; then
    OK=$((OK + 1)); printf '  \033[32m✔\033[0m %-62s %s%s\n' "$desc" "$STATUS" "${got_code:+ $got_code}"
  else
    MAL=$((MAL + 1)); printf '  \033[31m✘\033[0m %-62s esperado %s%s, llegó %s%s\n' "$desc" "$status" "${code:+ $code}" "$STATUS" "${got_code:+ $got_code}"
  fi
}

J='Content-Type: application/json'

titulo "0. Los dos servicios responden"
pedir GET "$CRONOS_URL/actuator/health"; esperar "cronos /actuator/health" 200
pedir GET "$ATLAS_URL/actuator/health";  esperar "atlas  /actuator/health" 200
if [ "$MAL" -gt 0 ]; then echo "Levantá cronos (:8081) y atlas (:8080) y volvé a correr el script." >&2; exit 1; fi

titulo "1. El cliente se registra en cronos"
pedir POST "$CRONOS_URL/api/register" -H "$J" -d "{\"login\":\"${LOGIN^^}\",\"password\":\"$CLAVE\"}"
esperar "registro con el login en MAYÚSCULAS" 201
printf '     → el servidor lo guardó como: login=%s activated=%s authorities=%s\n' \
  "$(printf '%s' "$BODY" | jq -r .login)" "$(printf '%s' "$BODY" | jq -r .activated)" "$(printf '%s' "$BODY" | jq -c .authorities)"
pedir POST "$CRONOS_URL/api/register" -H "$J" -d "{\"login\":\"$LOGIN\",\"password\":\"$CLAVE\"}"
esperar "el mismo login en minúsculas ya está usado" 400 USERNAME_ALREADY_EXISTS
pedir POST "$CRONOS_URL/api/register" -H "$J" -d '{"login":"ab","password":"x"}'
esperar "datos inválidos" 400 VALIDATION_ERROR

titulo "2. El cliente inicia sesión"
pedir POST "$CRONOS_URL/api/authenticate" -H "$J" -d "{\"username\":\"$LOGIN\",\"password\":\"otra-clave\"}"
esperar "contraseña equivocada" 401 INVALID_CREDENTIALS
MISMO1="$BODY"
pedir POST "$CRONOS_URL/api/authenticate" -H "$J" -d '{"username":"no.existe.nadie","password":"otra-clave"}'
esperar "login inexistente" 401 INVALID_CREDENTIALS
[ "$BODY" = "$MISMO1" ] && echo "     → mismo cuerpo que la contraseña equivocada: no revela qué logins existen" \
  || { echo "     ✘ los cuerpos difieren"; MAL=$((MAL + 1)); }
pedir POST "$CRONOS_URL/api/authenticate" -H "$J" -d "{\"username\":\"${LOGIN^^}\",\"password\":\"$CLAVE\"}"
esperar "credenciales correctas (el login no distingue mayúsculas)" 200
TOKEN=$(printf '%s' "$BODY" | jq -r '.id_token')
echo "     → token: ${TOKEN:0:24}…(${#TOKEN} caracteres)"
echo "     → cabecera: $(printf '%s' "$TOKEN" | cut -d. -f1 | tr '_-' '/+' | base64 -d 2>/dev/null)"
echo "     → claims:   $(printf '%s' "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | awk '{while(length($0)%4)$0=$0"=";print}' | base64 -d 2>/dev/null)"

titulo "3. El cliente consulta su cuenta en cronos"
AUTH=(-H "Authorization: Bearer $TOKEN")
pedir GET "$CRONOS_URL/api/account" "${AUTH[@]}"; esperar "GET /api/account con su token" 200
[ "$(printf '%s' "$BODY" | jq -r .login)" = "$LOGIN" ] && echo "     → es SU cuenta ($LOGIN)" || { echo "     ✘ cuenta de otro"; MAL=$((MAL + 1)); }
pedir GET "$CRONOS_URL/api/account?login=admin&id=1" "${AUTH[@]}"
[ "$(printf '%s' "$BODY" | jq -r .login)" = "$LOGIN" ] && esperar "pedir 'login=admin' no cambia de quién es la cuenta" 200 \
  || { STATUS=000; esperar "pedir 'login=admin' no cambia de quién es la cuenta" 200; }
pedir GET "$CRONOS_URL/api/account"; esperar "sin token" 401 NO_AUTENTICADO
pedir GET "$CRONOS_URL/api/otra-ruta" "${AUTH[@]}"; esperar "una ruta que cronos no habilita" 403 SIN_PERMISO

titulo "4. El MISMO token en atlas (prueba cruzada: cronos emite, atlas valida con la clave pública)"
pedir GET "$ATLAS_URL/api/categories" "${AUTH[@]}"; esperar "GET atlas /api/categories" 200
echo "     → $(printf '%s' "$BODY" | jq -r 'length') categorías, por ejemplo: $(printf '%s' "$BODY" | jq -r '[.[0:3][].name] | join(", ")')"
pedir GET "$ATLAS_URL/api/professionals?size=3" "${AUTH[@]}"; esperar "GET atlas /api/professionals" 200
echo "     → $(printf '%s' "$BODY" | jq -r .totalElements) profesionales en total"
pedir GET "$ATLAS_URL/api/sync/status" "${AUTH[@]}"; esperar "estado de la sincronización (solo ROLE_ADMIN)" 403 SIN_PERMISO

titulo "5. Qué pasa con un mal token (en cronos y en atlas)"
pedir GET "$ATLAS_URL/api/categories"; esperar "atlas sin token" 401 NO_AUTENTICADO
pedir GET "$ATLAS_URL/api/categories" -H "Authorization: Basic YW5hOmNsYXZl"; esperar "atlas con un esquema que no es Bearer" 401 NO_AUTENTICADO
ALT=$(alterar "$TOKEN")
pedir GET "$ATLAS_URL/api/categories" -H "Authorization: Bearer $ALT"; esperar "atlas con la firma alterada" 401 NO_AUTENTICADO
pedir GET "$CRONOS_URL/api/account" -H "Authorization: Bearer $ALT"; esperar "cronos con la firma alterada" 401 NO_AUTENTICADO
OTRA="$TMP/otra.pem"; openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OTRA" 2>/dev/null
FORJADO=$(forjar "$OTRA" "$LOGIN" ROLE_USER 3600)
pedir GET "$ATLAS_URL/api/categories" -H "Authorization: Bearer $FORJADO"; esperar "atlas con un token firmado con OTRA clave" 401 NO_AUTENTICADO
pedir GET "$CRONOS_URL/api/account" -H "Authorization: Bearer $FORJADO"; esperar "cronos con un token firmado con OTRA clave" 401 NO_AUTENTICADO
if [ -r "$DEV_PRIVATE_KEY" ]; then
  VENCIDO=$(forjar "$DEV_PRIVATE_KEY" "$LOGIN" ROLE_USER -7200)
  pedir GET "$ATLAS_URL/api/categories" -H "Authorization: Bearer $VENCIDO"; esperar "atlas con un token vencido (misma clave, hace 2 h)" 401 NO_AUTENTICADO
  pedir GET "$CRONOS_URL/api/account" -H "Authorization: Bearer $VENCIDO"; esperar "cronos con un token vencido" 401 NO_AUTENTICADO
  SINROL=$(forjar "$DEV_PRIVATE_KEY" "$LOGIN" ROLE_OTRO 3600)
  pedir GET "$ATLAS_URL/api/categories" -H "Authorization: Bearer $SINROL"; esperar "atlas con un token válido pero sin ROLE_USER" 403 SIN_PERMISO
else
  echo "  (se omiten el token vencido y el de rol insuficiente: no se puede leer DEV_PRIVATE_KEY=$DEV_PRIVATE_KEY)"
fi
pedir POST "$CRONOS_URL/api/authenticate" -H "$J" -H "Authorization: Bearer ${ALT}" -d "{\"username\":\"$LOGIN\",\"password\":\"$CLAVE\"}"
esperar "iniciar sesión mandando un token roto en el encabezado" 200

titulo "6. El cliente le pide a cronos los turnos libres de un profesional (horarios de atlas menos ocupaciones de la cátedra)"
pedir GET "$ATLAS_URL/api/professionals?available=true&size=1" "${AUTH[@]}"
PID=$(printf '%s' "$BODY" | jq -r '.content[0].id')
DESDE=$(date +%F); HASTA=$(date -d '+13 days' +%F)
DISP="$CRONOS_URL/api/professionals/$PID/availability"
pedir GET "$DISP?from=$DESDE&to=$HASTA" "${AUTH[@]}"; esperar "GET cronos disponibilidad del profesional $PID (14 días)" 200
printf '%s' "$BODY" | jq -r '"     → \(.slots | length) turnos libres; los primeros: \([.slots[:4][] | "\(.date) \(.startTime[:5])"] | join(", "))"' 2>/dev/null
pedir GET "$DISP?from=$DESDE&to=$HASTA"; esperar "disponibilidad sin token" 401 NO_AUTENTICADO
pedir GET "$DISP?from=$HASTA&to=$DESDE" "${AUTH[@]}"; esperar "disponibilidad con el rango invertido" 400 INVALID_DATE_RANGE
pedir GET "$DISP?from=hoy&to=$HASTA" "${AUTH[@]}"; esperar "disponibilidad con una fecha mal formada" 400 VALIDATION_ERROR
pedir GET "$CRONOS_URL/api/professionals/99999999/availability?from=$DESDE&to=$HASTA" "${AUTH[@]}"
esperar "disponibilidad de un profesional que no existe" 404 PROFESSIONAL_NOT_FOUND
echo "     (si la cátedra no responde, cronos contesta 503 CATEDRA_UNAVAILABLE y el primer paso de este bloque sale mal: es lo esperable)"

if [ "${LIMPIAR:-0}" = "1" ]; then
  titulo "7. Limpieza"
  docker exec cronos-db psql -U "${CRONOS_DB_USER:-cronos}" -d "${CRONOS_DB_NAME:-cronos}" -tc \
    "DELETE FROM app_user WHERE login = '$LOGIN';" || echo "  no se pudo borrar (¿otro usuario o base?)"
else
  printf '\nQuedó el usuario %s en la base de cronos. Para que la próxima corrida lo borre al terminar: LIMPIAR=1 scripts/recorrido-cliente.sh. O a mano:\n  docker exec cronos-db psql -U cronos -d cronos -c "DELETE FROM app_user WHERE login = '"'"'%s'"'"';"\n' "$LOGIN" "$LOGIN"
fi

titulo "Resumen"
printf '  %s comprobaciones: \033[32m%s bien\033[0m, \033[31m%s mal\033[0m\n' "$N" "$OK" "$MAL"
[ "$MAL" -eq 0 ]
