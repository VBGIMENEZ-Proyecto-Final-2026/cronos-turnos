#!/usr/bin/env bash
# SOLO DESARROLLO: deja lista la base de cronos (Podman o Docker) y arranca la aplicación en el puerto 8081 (o el de
# CRONOS_SERVER_PORT), en primer plano (Ctrl+C la detiene).
#
# Uso:   scripts/arrancar.sh
#
# Variables opcionales:
#   CRONOS_JWT_PRIVATE_KEY_LOCATION   clave privada con la que cronos firma los tokens
#                                     (por defecto ~/.atlas-dev/dev-private.pem, el par de DESARROLLO; atlas valida con
#                                     la pública de ese mismo par)
#
# Cronos necesita la cátedra para disponibilidad y reservas (REST, Redis y Kafka, por la VPN). Con la VPN caída arranca
# igual: registro, login y cuenta andan; lo que consulta a la cátedra contesta 503 CATEDRA_UNAVAILABLE.
#
# Requisitos: el archivo .env (copiar de .env.example), Podman o Docker con compose, y java 21 para gradlew.
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-}" in
  "") ;;
  -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
  *) echo "Opción desconocida: $1 (este script no recibe opciones)" >&2; exit 2 ;;
esac

[ -f .env ] || { echo "Falta el archivo .env: copiar .env.example y completarlo." >&2; exit 2; }

if command -v podman >/dev/null; then MOTOR=podman; elif command -v docker >/dev/null; then MOTOR=docker
else echo "Falta Podman o Docker." >&2; exit 2; fi

export CRONOS_JWT_PRIVATE_KEY_LOCATION="${CRONOS_JWT_PRIVATE_KEY_LOCATION:-$HOME/.atlas-dev/dev-private.pem}"
[ -f "$CRONOS_JWT_PRIVATE_KEY_LOCATION" ] || { echo "No existe la clave privada: $CRONOS_JWT_PRIVATE_KEY_LOCATION" >&2; exit 2; }

PUERTO="${CRONOS_SERVER_PORT:-8081}"
if ss -ltn 2>/dev/null | grep -q ":$PUERTO "; then
  echo "El puerto $PUERTO ya está ocupado (¿cronos ya está corriendo?). No se arranca otra copia." >&2; exit 1
fi

echo "== Base de datos (cronos-db)"
"$MOTOR" compose up -d db
for _ in $(seq 1 30); do
  estado=$("$MOTOR" inspect -f '{{.State.Health.Status}}' cronos-db 2>/dev/null || echo "?")
  [ "$estado" = "healthy" ] && break
  sleep 2
done
[ "$estado" = "healthy" ] || { echo "La base no quedó sana (estado: $estado)." >&2; exit 1; }
echo "   cronos-db lista"

echo "== Cronos en el puerto $PUERTO"
exec ./gradlew bootRun
