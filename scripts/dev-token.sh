#!/usr/bin/env bash
# Genera un JWT de desarrollo (HS256, válido 60 minutos) para probar los endpoints protegidos de Radar.
# Imita el token que emite IAM (JwtTokenIssuer): iss, aud, sub, role, iat y exp, firmado con la clave compartida.
#
# Uso:  scripts/dev-token.sh [rol] [usuario]
#   rol      GASTRONOMIC_EXPLORER (por defecto) o HUARIQUE_OWNER
#   usuario  identificador del usuario (por defecto dev-user)
#
# Variables:
#   JWT_SECRET_BASE64  OBLIGATORIA. La misma clave que usan IAM y Radar: Base64 estándar, mínimo 32 bytes
#                      decodificados. Genere una con:  openssl rand -base64 32
#   JWT_ISSUER         valor del claim iss (foodly-iam)
#   JWT_AUDIENCES      valores del claim aud, separados por comas (los de IAM: foodly-iam, foodly-business,
#                      foodly-radar, foodly-messaging, foodly-reservation y foodly-community)
#   JWT_USER_ID_CLAIM (sub) y JWT_ROLE_CLAIM (role)
# Requiere OpenSSL 1.1 o superior (en macOS, el de Homebrew: LibreSSL no sirve). Solo para desarrollo.

set -euo pipefail

command -v openssl >/dev/null || { echo "ERROR: falta openssl" >&2; exit 1; }

SECRET_BASE64="${JWT_SECRET_BASE64:-}"
if [ -z "$SECRET_BASE64" ]; then
  echo "ERROR: defina JWT_SECRET_BASE64 (la misma clave Base64 que usa IAM; genere una con: openssl rand -base64 32)" >&2
  exit 1
fi

# La clave se decodifica de Base64, igual que IAM y Radar: la firma usa los bytes, no el texto.
KEY_HEX=$(printf '%s' "$SECRET_BASE64" | openssl base64 -d -A | od -An -vtx1 | tr -d ' \n') \
  || { echo "ERROR: JWT_SECRET_BASE64 no es Base64 válido" >&2; exit 1; }
if [ "${#KEY_HEX}" -lt 64 ]; then
  echo "ERROR: JWT_SECRET_BASE64 debe tener al menos 32 bytes una vez decodificado" >&2
  exit 1
fi

ISSUER="${JWT_ISSUER:-foodly-iam}"
AUDIENCES="${JWT_AUDIENCES:-foodly-iam,foodly-business,foodly-radar,foodly-messaging,foodly-reservation,foodly-community}"
ROLE="${1:-GASTRONOMIC_EXPLORER}"
USER_ID="${2:-dev-user}"
USER_CLAIM="${JWT_USER_ID_CLAIM:-sub}"
ROLE_CLAIM="${JWT_ROLE_CLAIM:-role}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# "a,b,c" -> ["a","b","c"]
AUD_JSON=$(printf '%s' "$AUDIENCES" | awk -F, '{ printf "["; for (i = 1; i <= NF; i++) printf "%s\"%s\"", (i > 1 ? "," : ""), $i; printf "]" }')

now=$(date +%s)
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iss":"%s","aud":%s,"%s":"%s","%s":"%s","iat":%s,"exp":%s}' \
  "$ISSUER" "$AUD_JSON" "$USER_CLAIM" "$USER_ID" "$ROLE_CLAIM" "$ROLE" "$now" "$((now + 3600))" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" \
  | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$KEY_HEX" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
