#!/usr/bin/env bash
# Publica 20 eventos HuariqueRegistered (huariques de Miraflores, Barranco, Surquillo y Lince) en el exchange
# de eventos de Business, usando la API HTTP de management de RabbitMQ. No necesita rabbitmqadmin.
#
# Uso:  scripts/seed-events.sh [--reset] [--no-wait]
#   --reset    borra antes los huariques de prueba («seed-*») de Redis
#   --no-wait  no espera a que Radar los indexe
#
# Variables: MGMT_URL (http://localhost:15672), RABBIT_USER y RABBIT_PASSWORD (guest), COMPOSE_FILE.
# Es seguro ejecutarlo varias veces: los eventos son siempre los mismos (versión 1) y Radar descarta
# los repetidos. Por eso, si los huariques ya tienen una versión mayor (por ejemplo, después de la prueba
# PR-05), hay que usar --reset para volver a sembrarlos.

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib.sh
source scripts/lib.sh
# shellcheck source=scripts/seed-data.sh
source scripts/seed-data.sh

WAIT=true
RESET=false
for arg in "$@"; do
  case "$arg" in
    --no-wait) WAIT=false ;;
    --reset) RESET=true ;;
    *) die "opción desconocida: $arg (use --reset o --no-wait)" ;;
  esac
done

require_tools
wait_for_management

if [ "$RESET" = true ]; then
  log "Borrando los huariques de prueba de Redis..."
  cleanup_seed
fi

log "Publicando ${#SEED_HUARIQUES[@]} eventos HuariqueRegistered en $EXCHANGE..."
for row in "${SEED_HUARIQUES[@]}"; do
  IFS='|' read -r id district lat lng status <<<"$row"
  publish "$ROUTING_REGISTERED" "$(registered_event "$id" "$lat" "$lng" "$status")"
  printf '  %-22s %-10s %s\n' "$id" "$district" "$status"
done
log "Publicados ${#SEED_HUARIQUES[@]} eventos."

if [ "$WAIT" = true ]; then
  log "Esperando a que Radar los indexe en Redis..."
  expected=${#SEED_HUARIQUES[@]}
  for _ in $(seq 1 60); do
    indexed=$(redis_cli --scan --pattern "radar:huarique:${SEED_PREFIX}*" | grep -c . || true)
    [ "$indexed" -ge "$expected" ] && break
    sleep 1
  done
  [ "$indexed" -ge "$expected" ] || die "solo $indexed de $expected huariques indexados tras 60 s (revise «docker compose logs radar»)"
  log "Indexados $indexed de $expected huariques."
fi
