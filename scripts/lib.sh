#!/usr/bin/env bash
# Funciones comunes de los scripts de radar-service. Se carga con «source».

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.local.yml}"
MGMT_URL="${MGMT_URL:-http://localhost:15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASSWORD="${RABBIT_PASSWORD:-guest}"
VHOST_ENCODED="${VHOST_ENCODED:-%2F}"   # el vhost «/» codificado para la URL
EXCHANGE="${EXCHANGE:-${BUSINESS_EVENTS_EXCHANGE:-foodly.business}}"
QUEUE="${QUEUE:-radar.business-events}"
DLQ="${DLQ:-radar.business-events.dlq}"
SEED_PREFIX="${SEED_PREFIX:-seed-}"

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# «</dev/null» es imprescindible: «docker compose exec» lee la entrada estándar y, dentro de un
# «while read», se comería el resto de la lista que se está recorriendo.
redis_cli() { compose exec -T redis redis-cli "$@" </dev/null | tr -d '\r'; }

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_tools() {
  command -v curl >/dev/null || die "falta curl"
  command -v docker >/dev/null || die "falta docker"
  docker compose version >/dev/null 2>&1 || die "falta «docker compose» (Compose v2)"
}

# Espera a que la API de management de RabbitMQ responda.
wait_for_management() {
  local tries=60
  until curl -fsS -u "$RABBIT_USER:$RABBIT_PASSWORD" "$MGMT_URL/api/overview" >/dev/null 2>&1; do
    tries=$((tries - 1))
    [ "$tries" -gt 0 ] || die "la API de management no responde en $MGMT_URL (¿está levantado el compose?)"
    sleep 2
  done
}

# publish <routing_key> <json>: publica un mensaje persistente en el exchange de eventos de Business.
# El JSON del evento se envía como texto dentro del cuerpo de la API de management.
publish() {
  local routing_key="$1" payload="$2" escaped body response
  escaped=$(printf '%s' "$payload" | sed 's/\\/\\\\/g; s/"/\\"/g')
  body=$(printf '{"properties":{"content_type":"application/json","delivery_mode":2},"routing_key":"%s","payload":"%s","payload_encoding":"string"}' \
    "$routing_key" "$escaped")
  response=$(curl -fsS -u "$RABBIT_USER:$RABBIT_PASSWORD" -H 'content-type: application/json' \
    -X POST -d "$body" "$MGMT_URL/api/exchanges/$VHOST_ENCODED/$EXCHANGE/publish") \
    || die "no se pudo publicar en $EXCHANGE (routing key $routing_key)"
  case "$response" in
    *'"routed":true'*) ;;
    *) die "el mensaje no llegó a ninguna cola (routing key $routing_key): $response" ;;
  esac
}

# queue_stat <cola> <campo>: messages_ready, messages_unacknowledged o messages.
queue_stat() {
  local queue="$1" field="$2" json
  json=$(curl -fsS -u "$RABBIT_USER:$RABBIT_PASSWORD" "$MGMT_URL/api/queues/$VHOST_ENCODED/$queue") || { echo "-1"; return; }
  printf '%s' "$json" | grep -o "\"$field\":[0-9]*" | head -1 | cut -d: -f2
}

# Hash SHA-256 de lo que se lee por la entrada estándar (sha256sum en Linux y Git Bash, shasum en macOS).
sha256_of_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi
}

# Representación estable de todo lo que Radar guardó para los huariques de prueba: hashes y sets de celdas.
redis_state_dump() {
  local key
  redis_cli --scan --pattern "radar:huarique:${SEED_PREFIX}*" | LC_ALL=C sort | while read -r key; do
    [ -n "$key" ] || continue
    echo "== $key"
    redis_cli hgetall "$key" | paste - - | LC_ALL=C sort
  done
  redis_cli --scan --pattern 'radar:cell:*' | LC_ALL=C sort | while read -r key; do
    [ -n "$key" ] || continue
    echo "== $key"
    redis_cli smembers "$key" | LC_ALL=C sort
  done
}
