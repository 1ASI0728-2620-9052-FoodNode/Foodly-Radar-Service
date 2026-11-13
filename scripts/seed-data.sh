#!/usr/bin/env bash
# Datos de prueba y constructores de eventos. Se carga con «source» (después de lib.sh).

# 20 huariques de ejemplo, 5 por distrito. Las coordenadas son aproximadas.
# Formato: id|distrito|latitud|longitud|estado
SEED_HUARIQUES=(
  "${SEED_PREFIX}miraflores-01|Miraflores|-12.1219|-77.0297|ACTIVE"   # Parque Kennedy
  "${SEED_PREFIX}miraflores-02|Miraflores|-12.1318|-77.0300|ACTIVE"   # Larcomar
  "${SEED_PREFIX}miraflores-03|Miraflores|-12.1245|-77.0280|ACTIVE"   # Av. Larco
  "${SEED_PREFIX}miraflores-04|Miraflores|-12.1170|-77.0345|ACTIVE"   # Óvalo Gutiérrez
  "${SEED_PREFIX}miraflores-05|Miraflores|-12.1260|-77.0372|INACTIVE"
  "${SEED_PREFIX}barranco-01|Barranco|-12.1498|-77.0207|ACTIVE"       # Plaza de Barranco
  "${SEED_PREFIX}barranco-02|Barranco|-12.1505|-77.0236|ACTIVE"       # Puente de los Suspiros
  "${SEED_PREFIX}barranco-03|Barranco|-12.1520|-77.0260|ACTIVE"
  "${SEED_PREFIX}barranco-04|Barranco|-12.1480|-77.0220|ACTIVE"
  "${SEED_PREFIX}barranco-05|Barranco|-12.1470|-77.0240|INACTIVE"
  "${SEED_PREFIX}surquillo-01|Surquillo|-12.1120|-77.0110|ACTIVE"     # Mercado de Surquillo
  "${SEED_PREFIX}surquillo-02|Surquillo|-12.1100|-77.0150|ACTIVE"
  "${SEED_PREFIX}surquillo-03|Surquillo|-12.1160|-77.0120|ACTIVE"
  "${SEED_PREFIX}surquillo-04|Surquillo|-12.1090|-77.0080|ACTIVE"
  "${SEED_PREFIX}surquillo-05|Surquillo|-12.1180|-77.0090|INACTIVE"
  "${SEED_PREFIX}lince-01|Lince|-12.0850|-77.0340|ACTIVE"
  "${SEED_PREFIX}lince-02|Lince|-12.0870|-77.0330|ACTIVE"
  "${SEED_PREFIX}lince-03|Lince|-12.0920|-77.0340|ACTIVE"
  "${SEED_PREFIX}lince-04|Lince|-12.0830|-77.0360|ACTIVE"
  "${SEED_PREFIX}lince-05|Lince|-12.0865|-77.0310|INACTIVE"
)

seed_ids() { local row; for row in "${SEED_HUARIQUES[@]}"; do printf '%s\n' "${row%%|*}"; done; }

# Borra de Redis los huariques de prueba y sus ids en los sets de celdas, de forma atómica.
# Solo toca los ids de SEED_HUARIQUES; el resto de los datos queda intacto.
cleanup_seed() {
  local lua="for _, id in ipairs(ARGV) do
    local cell = redis.call('HGET', 'radar:huarique:' .. id, 'h3Cell')
    if cell then redis.call('SREM', 'radar:cell:' .. cell, id) end
    redis.call('DEL', 'radar:huarique:' .. id)
  end return #ARGV"
  # shellcheck disable=SC2046
  redis_cli eval "$lua" 0 $(seed_ids) >/dev/null
}

ROUTING_REGISTERED="huarique.registered"
ROUTING_LOCATION_UPDATED="huarique.location-updated"
ROUTING_OPENING_STATUS="huarique.opening-status-changed"

now_iso() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }

# Los eventId son deterministas: republicar el mismo evento es exactamente el mismo mensaje.

# registered_event <id> <lat> <lng> <status>  (versión 1)
registered_event() {
  printf '{"eventId":"seed-registered-%s-v1","eventType":"HuariqueRegistered","huariqueId":"%s","version":1,"occurredAt":"%s","payload":{"latitude":%s,"longitude":%s,"status":"%s"}}' \
    "$1" "$1" "$(now_iso)" "$2" "$3" "$4"
}

# location_updated_event <id> <lat> <lng> <status> <version>
location_updated_event() {
  printf '{"eventId":"seed-location-%s-v%s","eventType":"LocationUpdated","huariqueId":"%s","version":%s,"occurredAt":"%s","payload":{"latitude":%s,"longitude":%s,"status":"%s"}}' \
    "$1" "$5" "$1" "$5" "$(now_iso)" "$2" "$3" "$4"
}

# opening_status_event <id> <status> <version>
opening_status_event() {
  printf '{"eventId":"seed-opening-%s-v%s","eventType":"OpeningStatusChanged","huariqueId":"%s","version":%s,"occurredAt":"%s","payload":{"status":"%s"}}' \
    "$1" "$3" "$1" "$3" "$(now_iso)" "$2"
}
