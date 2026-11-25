#!/usr/bin/env bash
# PR-05: Radar caído mientras Business sigue publicando eventos.
#
#   1. Indexa 20 huariques (línea base, aggregateVersion 0).
#   2. Detiene Radar («docker compose stop radar») y registra la hora de la falla.
#   3. Publica 20 eventos (PerfilDelHuariqueActualizado y EstadoAbiertoCerradoCambiado, aggregateVersion 1) con
#      Radar caído:
#      deben esperar en la cola durable.
#   4. Levanta Radar, registra la hora de la recuperación y verifica que se aplicaron 20 de 20.
#   5. Republica los mismos 20 eventos y verifica que Redis no cambió (0 duplicados).
#   6. Verifica que la cola y la DLQ quedan en 0.
#
# Uso:  scripts/pr05-consumer-down.sh
# Variables: PR05_TIMEOUT (segundos de espera por paso, 120), MGMT_URL, COMPOSE_FILE.
# Borra solo los huariques de prueba (prefijo «seed-») y vacía la cola de Radar al empezar.
# Termina con código 0 si todas las verificaciones pasan y 1 si alguna falla.

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib.sh
source scripts/lib.sh
# shellcheck source=scripts/seed-data.sh
source scripts/seed-data.sh

TIMEOUT="${PR05_TIMEOUT:-120}"
EXPECTED=${#SEED_HUARIQUES[@]}
FAILED_CHECKS=0
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

stamp() { date '+%H:%M:%S'; }
epoch() { date +%s; }

check() { # check "<descripción>" <0 si pasa, otro valor si falla> "<detalle>"
  if [ "$2" -eq 0 ]; then
    printf '  [PASA]  %s\n' "$1"
  else
    printf '  [FALLA] %s%s\n' "$1" "${3:+  ($3)}"
    FAILED_CHECKS=$((FAILED_CHECKS + 1))
  fi
}

# Redondea un número a su forma más corta, para comparar «-12.11» con «-12.1100».
norm() { awk -v x="$1" 'BEGIN { printf "%.10g", x + 0 }'; }

# Cuántos huariques de prueba tienen la versión indicada en su hash.
count_with_version() {
  local lua="local n = 0
  for _, id in ipairs(ARGV) do
    if redis.call('HGET', 'radar:huarique:' .. id, 'version') == '$1' then n = n + 1 end
  end return n"
  # shellcheck disable=SC2046
  redis_cli eval "$lua" 0 $(seed_ids)
}

# Una línea por huarique: id|version|lat|status
actual_fields() {
  local lua="local out = {}
  for _, id in ipairs(ARGV) do
    local h = redis.call('HMGET', 'radar:huarique:' .. id, 'version', 'lat', 'status')
    table.insert(out, id .. '|' .. tostring(h[1] or '') .. '|' .. tostring(h[2] or '') .. '|' .. tostring(h[3] or ''))
  end return out"
  # shellcheck disable=SC2046
  redis_cli eval "$lua" 0 $(seed_ids) | while IFS='|' read -r id version lat status; do
    printf '%s|%s|%s|%s\n' "$id" "$version" "$(norm "${lat:-0}")" "$status"
  done | LC_ALL=C sort
}

# Espera hasta que "$@" devuelva 0 o se agote el tiempo. Devuelve 0 si se cumplió.
wait_until() {
  local deadline=$(( $(epoch) + TIMEOUT ))
  until "$@"; do
    [ "$(epoch)" -lt "$deadline" ] || return 1
    sleep 2
  done
}

queue_ready_is() { [ "$(queue_stat "$QUEUE" messages_ready)" = "$1" ]; }
queue_is_empty() {
  [ "$(queue_stat "$QUEUE" messages_ready)" = "0" ] && [ "$(queue_stat "$QUEUE" messages_unacknowledged)" = "0" ]
}
applied_is_complete() { [ "$(count_with_version 1)" = "$EXPECTED" ]; }
baseline_is_complete() { [ "$(count_with_version 0)" = "$EXPECTED" ]; }
discarded_logs() { compose logs radar --no-log-prefix 2>/dev/null | grep -c 'Descartado: la versión' || true; }
discards_reached() { [ "$(discarded_logs)" -ge "$1" ]; }

# publish_round: publica los 20 eventos de la prueba y escribe en $WORK_DIR/expected los valores esperados.
publish_round() {
  local row id district lat lng status i=0 new_lat new_status
  : >"$WORK_DIR/expected"
  for row in "${SEED_HUARIQUES[@]}"; do
    IFS='|' read -r id district lat lng status <<<"$row"
    i=$((i + 1))
    if [ $((i % 2)) -eq 1 ]; then
      # Impares: el dueño cambia las coordenadas (el huarique se mueve de celda si cruza el límite).
      new_lat=$(awk -v x="$lat" 'BEGIN { printf "%.4f", x + 0.0010 }')
      publish "$ROUTING_UPDATED" "$(profile_updated_event "$id" "$new_lat" "$lng" "$status" 1)"
      printf '%s|1|%s|%s\n' "$id" "$(norm "$new_lat")" "$status" >>"$WORK_DIR/expected"
    else
      # Pares: el huarique abre o cierra.
      if [ "$status" = "ACTIVE" ]; then new_status=INACTIVE; else new_status=ACTIVE; fi
      publish "$ROUTING_OPENING_STATUS" "$(opening_status_event "$id" "$lat" "$lng" "$new_status" 1)"
      printf '%s|1|%s|%s\n' "$id" "$(norm "$lat")" "$new_status" >>"$WORK_DIR/expected"
    fi
  done
  LC_ALL=C sort -o "$WORK_DIR/expected" "$WORK_DIR/expected"
}

# ---------------------------------------------------------------------------------------------------------------

require_tools
echo "=== PR-05: Radar caído con eventos pendientes ==="

log "Levantando redis, rabbitmq y radar..."
compose up -d --wait >/dev/null
wait_for_management

log "Preparando el estado inicial (borra los huariques «$SEED_PREFIX*» y vacía la cola de Radar)..."
curl -fsS -u "$RABBIT_USER:$RABBIT_PASSWORD" -X DELETE "$MGMT_URL/api/queues/$VHOST_ENCODED/$QUEUE/contents" >/dev/null || true
cleanup_seed

# --- 1. Línea base ---
log "1/6 Línea base: $EXPECTED eventos HuariqueRegistrado (aggregateVersion 0)"
for row in "${SEED_HUARIQUES[@]}"; do
  IFS='|' read -r id district lat lng status <<<"$row"
  publish "$ROUTING_REGISTERED" "$(registered_event "$id" "$lat" "$lng" "$status")"
done
wait_until baseline_is_complete || die "la línea base no se indexó en ${TIMEOUT}s (revise «docker compose logs radar»)"
log "    $EXPECTED de $EXPECTED huariques indexados."

# --- 2. Falla ---
log "2/6 Deteniendo Radar..."
compose stop radar >/dev/null
FAIL_EPOCH=$(epoch); FAIL_TIME=$(stamp)
log "    FALLA: Radar detenido a las $FAIL_TIME."

# --- 3. Eventos con Radar caído ---
log "3/6 Publicando $EXPECTED eventos (PerfilDelHuariqueActualizado y EstadoAbiertoCerradoCambiado, aggregateVersion 1) con Radar caído..."
publish_round
wait_until queue_ready_is "$EXPECTED" || true
QUEUED=$(queue_stat "$QUEUE" messages_ready)
CONSUMERS=$(queue_stat "$QUEUE" consumers)
log "    Mensajes esperando en la cola: $QUEUED. Consumidores conectados: $CONSUMERS."
check "los $EXPECTED eventos esperan en la cola durable mientras Radar está caído" \
  "$([ "$QUEUED" = "$EXPECTED" ] && echo 0 || echo 1)" "mensajes en cola: $QUEUED"
check "no hay consumidores mientras Radar está caído" "$([ "$CONSUMERS" = "0" ] && echo 0 || echo 1)" "consumidores: $CONSUMERS"
check "Redis no cambió mientras Radar estuvo caído" \
  "$([ "$(count_with_version 1)" = "0" ] && echo 0 || echo 1)" "huariques ya en versión 1: $(count_with_version 1)"

# --- 4. Recuperación ---
log "4/6 Levantando Radar..."
compose start radar >/dev/null
UP_EPOCH=$(epoch); UP_TIME=$(stamp)
log "    RECUPERACIÓN: Radar iniciado a las $UP_TIME."
wait_until applied_is_complete || true
DONE_EPOCH=$(epoch)
APPLIED=$(count_with_version 1)
log "    Eventos aplicados: $APPLIED de $EXPECTED."

actual_fields >"$WORK_DIR/actual"
DIFF=$(diff "$WORK_DIR/expected" "$WORK_DIR/actual" || true)
check "se aplicaron $EXPECTED de $EXPECTED eventos" "$([ "$APPLIED" = "$EXPECTED" ] && echo 0 || echo 1)" "aplicados: $APPLIED"
check "los valores en Redis coinciden con los esperados (coordenadas y estado)" "$([ -z "$DIFF" ] && echo 0 || echo 1)" "$(printf '%s' "$DIFF" | head -3 | tr '\n' ' ')"

# --- 5. Republicación ---
log "5/6 Republicando los mismos $EXPECTED eventos..."
wait_until queue_is_empty || true
redis_state_dump >"$WORK_DIR/state_before"
DISCARDS_BEFORE=$(discarded_logs)
REPUBLISH_EPOCH=$(epoch)
publish_round
wait_until discards_reached $((DISCARDS_BEFORE + EXPECTED)) || true
wait_until queue_is_empty || true
REPUBLISH_SECONDS=$(( $(epoch) - REPUBLISH_EPOCH ))
redis_state_dump >"$WORK_DIR/state_after"
STATE_BEFORE=$(sha256_of_stdin <"$WORK_DIR/state_before")
STATE_AFTER=$(sha256_of_stdin <"$WORK_DIR/state_after")
DUMPED_HASHES=$(grep -c '^== radar:huarique:' "$WORK_DIR/state_before" || true)
DISCARDED=$(( $(discarded_logs) - DISCARDS_BEFORE ))
log "    Eventos descartados por versión repetida: $DISCARDED de $EXPECTED."

IDS_IN_CELLS=$(redis_cli --scan --pattern 'radar:cell:*' | while read -r key; do [ -n "$key" ] && redis_cli smembers "$key"; done | grep -c "^$SEED_PREFIX" || true)
UNIQUE_IDS=$(redis_cli --scan --pattern 'radar:cell:*' | while read -r key; do [ -n "$key" ] && redis_cli smembers "$key"; done | grep "^$SEED_PREFIX" | sort -u | grep -c . || true)
DUPLICATES=$((IDS_IN_CELLS - UNIQUE_IDS))
check "Redis no cambió al republicar (hashes y sets idénticos, incluido updatedAt)" \
  "$([ "$STATE_BEFORE" = "$STATE_AFTER" ] && [ "$DUMPED_HASHES" = "$EXPECTED" ] && echo 0 || echo 1)" \
  "hashes comparados: $DUMPED_HASHES de $EXPECTED; ${STATE_BEFORE:0:8} vs ${STATE_AFTER:0:8}"
check "los $EXPECTED eventos republicados se descartaron por versión" "$([ "$DISCARDED" = "$EXPECTED" ] && echo 0 || echo 1)" "descartados: $DISCARDED"
check "0 duplicados en los sets de celdas" "$([ "$DUPLICATES" = "0" ] && [ "$UNIQUE_IDS" = "$EXPECTED" ] && echo 0 || echo 1)" \
  "ids en sets: $IDS_IN_CELLS, únicos: $UNIQUE_IDS"

# --- 6. Cola ---
log "6/6 Verificando la cola..."
READY=$(queue_stat "$QUEUE" messages_ready); UNACKED=$(queue_stat "$QUEUE" messages_unacknowledged)
DLQ_COUNT=$(queue_stat "$DLQ" messages_ready)
check "la cola $QUEUE queda en 0 (listos: $READY, sin confirmar: $UNACKED)" \
  "$([ "$READY" = "0" ] && [ "$UNACKED" = "0" ] && echo 0 || echo 1)"
check "la DLQ $DLQ queda en 0 (mensajes: $DLQ_COUNT)" "$([ "$DLQ_COUNT" = "0" ] && echo 0 || echo 1)"

# --- Métricas ---
echo
echo "=== Métricas ==="
printf '  Huariques de prueba                         %s\n' "$EXPECTED"
printf '  Hora de la falla (Radar detenido)           %s\n' "$FAIL_TIME"
printf '  Hora de la recuperación (Radar iniciado)    %s\n' "$UP_TIME"
printf '  Tiempo con Radar caído                      %s s\n' "$((UP_EPOCH - FAIL_EPOCH))"
printf '  Eventos publicados con Radar caído          %s\n' "$EXPECTED"
printf '  Mensajes en cola al reiniciar               %s\n' "$QUEUED"
printf '  Eventos aplicados tras la recuperación      %s/%s\n' "$APPLIED" "$EXPECTED"
printf '  Tiempo desde el reinicio hasta 20/20        %s s\n' "$((DONE_EPOCH - UP_EPOCH))"
printf '  Eventos republicados descartados            %s/%s (en %s s)\n' "$DISCARDED" "$EXPECTED" "$REPUBLISH_SECONDS"
printf '  Duplicados en Redis                         %s\n' "$DUPLICATES"
printf '  Cola %s   %s listos, %s sin confirmar\n' "$QUEUE" "$READY" "$UNACKED"
printf '  DLQ  %s   %s\n' "$DLQ" "$DLQ_COUNT"
echo

if [ "$FAILED_CHECKS" -eq 0 ]; then
  echo "RESULTADO: PR-05 APROBADA"
else
  echo "RESULTADO: PR-05 FALLÓ ($FAILED_CHECKS verificaciones)"
  exit 1
fi
