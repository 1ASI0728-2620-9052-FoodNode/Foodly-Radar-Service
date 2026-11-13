# Evidencias de radar-service (Cap. VII)

Evidencias del **Geo-Radar Engine Service (GRES)**, el contexto Radar de Foodly. Todos los ejemplos de este documento
se obtuvieron ejecutando el servicio con `docker-compose.local.yml` el 2026-11-13; las respuestas son las reales.

| Evidencia | Dónde |
|---|---|
| Revisión contra el informe (elemento, archivo, estado) | [`docs/VERIFICACION.md`](VERIFICACION.md) |
| Diferencias con el informe y texto exacto para corregirlo | [`DEVIATIONS.md`](../DEVIATIONS.md) (sección D) |
| Prueba PR-05 (Radar caído con eventos pendientes) | [`docs/evidencias/pr05-2026-11-13.txt`](evidencias/pr05-2026-11-13.txt) |
| Resultado de `mvn verify` | sección 5 de este documento |

## 1. Preparar el entorno

```bash
docker compose -f docker-compose.local.yml up -d --build --wait    # Redis, RabbitMQ y Radar
scripts/seed-events.sh --reset                                       # 20 huariques de ejemplo
TOKEN=$(scripts/dev-token.sh)                                        # JWT de un Explorador Gastronómico
```

Radar queda en `http://localhost:8080`, Swagger en `http://localhost:8080/swagger-ui.html` y la consola de RabbitMQ
en `http://localhost:15672` (usuario y contraseña `guest`). Más detalles en el [`README.md`](../README.md).

## 2. Endpoints

| Método | Ruta | Descripción | Acceso | Códigos |
|---|---|---|---|---|
| POST | `/radar/search` | Huariques activos de la zona de cobertura, ordenados por distancia | JWT con rol `GASTRONOMIC_EXPLORER` | 200, 400, 401, 403 |
| GET | `/radar/status` | Estado del servicio: UP si Radar puede atender búsquedas (Redis disponible) | Público | 200, 503 |
| GET | `/actuator/health` | Salud de Redis y RabbitMQ (la usa el `HEALTHCHECK` de Docker) | Público | 200, 503 |
| GET | `/swagger-ui.html` | Documentación interactiva (redirige a `/swagger-ui/index.html`) | Público | 302, 200 |
| GET | `/v3/api-docs` | Especificación OpenAPI en JSON | Público | 200 |

Todos los errores tienen el formato `{ status, error, message, path, timestamp }`, con el mensaje en español.

### POST /radar/search

**Request** (`Authorization: Bearer <JWT>`, `Content-Type: application/json`):

```json
{ "latitude": -12.1219, "longitude": -77.0297, "kRing": 3 }
```

| Campo | Tipo | Regla |
|---|---|---|
| `latitude` | número | obligatorio, entre -90 y 90 |
| `longitude` | número | obligatorio, entre -180 y 180 |
| `kRing` | entero | obligatorio, de 0 a 5 (`RADAR_MAX_K_RING`) |

**200 OK**: huariques activos de la zona, del más cercano al más lejano (`distanceMeters` en metros):

```json
{
  "huariques": [
    { "huariqueId": "seed-miraflores-01", "latitude": -12.1219, "longitude": -77.0297, "distanceMeters": 0.0 },
    { "huariqueId": "seed-miraflores-03", "latitude": -12.1245, "longitude": -77.028,  "distanceMeters": 343.1324773185726 },
    { "huariqueId": "seed-miraflores-04", "latitude": -12.117,  "longitude": -77.0345, "distanceMeters": 754.4437891263215 },
    { "huariqueId": "seed-miraflores-02", "latitude": -12.1318, "longitude": -77.03,   "distanceMeters": 1101.3143146694085 }
  ],
  "total": 4
}
```

**200 OK, zona sin huariques**:

```json
{ "huariques": [], "total": 0 }
```

**400 Bad Request** (latitud fuera de rango; con `kRing` 9 el detalle es «el k-ring debe estar entre 0 y 5»):

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "No se pudo procesar la consulta del radar: la latitud debe estar entre -90 y 90",
  "path": "/radar/search",
  "timestamp": "2026-11-13T22:25:14.853456626Z"
}
```

**401 Unauthorized** (sin token, firma inválida o token vencido):

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Debe iniciar sesión: falta el token de acceso o no es válido.",
  "path": "/radar/search",
  "timestamp": "2026-11-13T22:25:15.360056282Z"
}
```

**403 Forbidden** (token válido con rol `HUARIQUE_OWNER`):

```json
{
  "status": 403,
  "error": "Forbidden",
  "message": "No tiene permisos para realizar esta operación: se requiere el rol de explorador gastronómico.",
  "path": "/radar/search",
  "timestamp": "2026-11-13T22:25:15.606724189Z"
}
```

### GET /radar/status

```bash
curl -i http://localhost:8080/radar/status
```

- **200 OK**: `{ "status": "UP" }`
- **503 Service Unavailable**: `{ "status": "DOWN" }`. Se obtuvo deteniendo Redis (`docker compose -f docker-compose.local.yml stop redis`).

### GET /actuator/health

- **200 OK**: `{ "status": "UP" }`
- **503 Service Unavailable**: `{ "status": "DOWN" }` si Redis o RabbitMQ no responden.

## 3. Capturas sugeridas de Swagger

Abrir `http://localhost:8080/swagger-ui.html` con el servicio levantado y guardar las capturas en `docs/evidencias/`.

| # | Archivo sugerido | Qué debe verse |
|---|---|---|
| 1 | `swagger-01-vista-general.png` | Título «Foodly - Geo-Radar Engine Service (GRES)», la etiqueta **Radar** con sus dos operaciones y el botón **Authorize** |
| 2 | `swagger-02-authorize.png` | El cuadro **Authorize** con el esquema `bearerAuth` (HTTP, bearer, JWT) y el token pegado |
| 3 | `swagger-03-search-detalle.png` | `POST /radar/search` expandido: descripción, el cuerpo de ejemplo `{ "latitude": -12.1219, "longitude": -77.0297, "kRing": 2 }` y las respuestas 200, 400, 401 y 403 |
| 4 | `swagger-04-search-200.png` | **Try it out** con la respuesta 200 y la lista de huariques (US-11) |
| 5 | `swagger-05-search-vacio.png` | Respuesta 200 con `{ "huariques": [], "total": 0 }` para una zona sin huariques (US-12) |
| 6 | `swagger-06-search-400.png` | Respuesta 400 con el mensaje «No se pudo procesar la consulta del radar: …» |
| 7 | `swagger-07-search-401-403.png` | Respuestas 401 (sin Authorize) y 403 (token de `HUARIQUE_OWNER`) |
| 8 | `swagger-08-status.png` | `GET /radar/status` con 200 `{ "status": "UP" }` |
| 9 | `swagger-09-schemas.png` | Sección **Schemas**: `RadarSearchResource`, `RadarSearchResultResource`, `HuariqueLocationResource`, `RadarStatusResource` y `ErrorResponse` |

Capturas complementarias de RabbitMQ (`http://localhost:15672`):

| # | Archivo sugerido | Qué debe verse |
|---|---|---|
| 10 | `rabbitmq-01-colas.png` | Pestaña **Queues**: `radar.business-events` y `radar.business-events.dlq`, ambas durables |
| 11 | `rabbitmq-02-bindings.png` | Detalle del exchange `foodly.business.events` con sus cuatro bindings hacia la cola de Radar |
| 12 | `rabbitmq-03-cola-con-radar-caido.png` | Durante PR-05: 20 mensajes listos y 0 consumidores en `radar.business-events` |

## 4. Cómo probar cada User Story

**Nota sobre las historias.** El extracto del informe (`docs/informe/informe-radar-messaging.md`) no incluye el texto de
las historias de usuario. La correspondencia de **US-12** con la zona sin huariques («No hay huariques aquí aún»,
RF-13) viene de la corrección del informe registrada en `DEVIATIONS.md` (sección B, fila 3). **US-11** se interpretó
como la búsqueda de huariques cercanos en el radar; **conviene confirmar el enunciado exacto de ambas** y ajustar los
títulos de esta sección si difieren.

### US-11: Ver los huariques cercanos en el radar

*(Enunciado asumido: como Explorador Gastronómico, quiero ver los huariques abiertos cerca de mi ubicación, ordenados
por distancia.)*

**Preparación:** entorno de la sección 1 (20 huariques sembrados; `seed-miraflores-05` está inactivo).

**Paso 1. Buscar cerca de Parque Kennedy (Miraflores) con 3 anillos**

```bash
curl -s -X POST http://localhost:8080/radar/search \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{ "latitude": -12.1219, "longitude": -77.0297, "kRing": 3 }'
```

*Resultado esperado:* 200 con 4 huariques, del más cercano al más lejano: `seed-miraflores-01` (0 m),
`seed-miraflores-03` (343 m), `seed-miraflores-04` (754 m) y `seed-miraflores-02` (1 101 m). `seed-miraflores-05`
**no aparece** porque está inactivo y los huariques de Barranco, Surquillo y Lince quedan fuera de la zona.

**Paso 2. Reducir la zona**

```bash
curl -s -X POST http://localhost:8080/radar/search -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{ "latitude": -12.1219, "longitude": -77.0297, "kRing": 1 }'
```

*Resultado esperado:* 200 con 2 huariques (`seed-miraflores-01` y `seed-miraflores-03`): menos anillos, zona menor.

**Paso 3. Un huarique cierra y desaparece del radar** (evento `OpeningStatusChanged` de Business)

```bash
curl -s -u guest:guest -H 'content-type: application/json' -X POST \
  http://localhost:15672/api/exchanges/%2F/foodly.business.events/publish -d '{
  "properties": {"delivery_mode": 2, "content_type": "application/json"},
  "routing_key": "huarique.opening-status-changed",
  "payload": "{\"eventId\":\"demo-cierre-1\",\"eventType\":\"OpeningStatusChanged\",\"huariqueId\":\"seed-miraflores-03\",\"version\":2,\"occurredAt\":\"2026-11-13T12:00:00Z\",\"payload\":{\"status\":\"INACTIVE\"}}",
  "payload_encoding": "string"}'
```

*Resultado esperado:* `{"routed":true}`. Repetir el paso 1: ahora devuelve 3 huariques y `seed-miraflores-03` ya no está.

**Paso 4. Un huarique cambia de ubicación** (evento `LocationUpdated`)

```bash
curl -s -u guest:guest -H 'content-type: application/json' -X POST \
  http://localhost:15672/api/exchanges/%2F/foodly.business.events/publish -d '{
  "properties": {"delivery_mode": 2, "content_type": "application/json"},
  "routing_key": "huarique.location-updated",
  "payload": "{\"eventId\":\"demo-mover-1\",\"eventType\":\"LocationUpdated\",\"huariqueId\":\"seed-miraflores-02\",\"version\":2,\"occurredAt\":\"2026-11-13T12:00:00Z\",\"payload\":{\"latitude\":-12.1498,\"longitude\":-77.0207,\"status\":\"ACTIVE\"}}",
  "payload_encoding": "string"}'
```

*Resultado esperado:* en Miraflores (paso 1) quedan 2 huariques; al buscar en la Plaza de Barranco
(`"latitude": -12.1498, "longitude": -77.0207, "kRing": 1`) aparecen 5, entre ellos `seed-miraflores-02` a 0 m.

**Paso 5. Seguridad**

```bash
# Sin token: 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/radar/search \
  -H 'Content-Type: application/json' -d '{ "latitude": -12.1219, "longitude": -77.0297, "kRing": 2 }'
# Dueño de huarique: 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/radar/search \
  -H "Authorization: Bearer $(scripts/dev-token.sh HUARIQUE_OWNER)" -H 'Content-Type: application/json' \
  -d '{ "latitude": -12.1219, "longitude": -77.0297, "kRing": 2 }'
```

*Resultado esperado:* `401` y `403`.

**Paso 6. Datos inválidos**

```bash
curl -s -X POST http://localhost:8080/radar/search -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{ "latitude": 91, "longitude": -77.0297, "kRing": 2 }'
```

*Resultado esperado:* 400 con «No se pudo procesar la consulta del radar: la latitud debe estar entre -90 y 90».

**Pruebas automáticas que lo cubren:** `RadarControllerTest`, `RadarSearchServiceTest`,
`SearchNearbyHuariquesQueryHandlerTest`, `RadarServiceApplicationIntegrationTest` y `BusinessEventsIntegrationTest`.

### US-12: Zona sin huariques («No hay huariques aquí aún»)

*(Enunciado asumido: como Explorador Gastronómico, quiero que el radar me avise cuando no hay huariques en la zona,
para saberlo en vez de ver una pantalla vacía. RF-13.)*

Radar no devuelve el texto «No hay huariques aquí aún»: responde con una **lista vacía y `total` 0**, y el frontend
muestra el mensaje (5.2.2).

**Paso 1. Una zona donde no hay ningún huarique (Cusco)**

```bash
curl -i -X POST http://localhost:8080/radar/search -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{ "latitude": -13.5319, "longitude": -71.9675, "kRing": 2 }'
```

*Resultado esperado:* `HTTP/1.1 200` y `{"huariques":[],"total":0}`. No es un error: no hay 404.

**Paso 2. Una zona donde solo hay huariques inactivos**

`seed-lince-05` está inactivo y es el único huarique de su celda:

```bash
curl -s -X POST http://localhost:8080/radar/search -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{ "latitude": -12.0865, "longitude": -77.0310, "kRing": 0 }'
```

*Resultado esperado:* 200 y `{"huariques":[],"total":0}`. Con `"kRing": 1` aparecen los vecinos activos
`seed-lince-02` (224 m) y `seed-lince-01` (366 m): la lista deja de estar vacía al ampliar la zona.

**Paso 3. Se registra un huarique y la zona deja de estar vacía** (opcional)

Publique un evento `HuariqueRegistered` con coordenadas en la zona vacía (`scripts/seed-events.sh` muestra el formato)
y repita el paso 1: la respuesta incluye al nuevo huarique.

**Pruebas automáticas que lo cubren:** `RadarControllerTest` (200 con lista vacía),
`RadarSearchServiceTest` (lista vacía y evento `NoHuariquesFound`) y `RadarServiceApplicationIntegrationTest`.

## 5. Resultados de las pruebas

### `mvn verify`

```
Tests run: 218, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

22 clases de prueba, incluidas las de integración con Testcontainers (Redis y RabbitMQ reales). El mismo comando
construye el jar ejecutable.

### PR-05: Radar caído con eventos pendientes

Salida completa en [`docs/evidencias/pr05-2026-11-13.txt`](evidencias/pr05-2026-11-13.txt). Resumen de la ejecución
del 2026-11-13:

| Métrica | Valor |
|---|---|
| Hora de la falla (Radar detenido) | 17:23:01 |
| Hora de la recuperación (Radar iniciado) | 17:23:17 |
| Tiempo con Radar caído | 16 s |
| Eventos publicados con Radar caído | 20 |
| Mensajes en cola al reiniciar | 20 |
| Eventos aplicados tras la recuperación | 20 de 20 |
| Tiempo desde el reinicio hasta 20 de 20 | 9 s |
| Eventos republicados descartados por versión | 20 de 20 |
| Duplicados en Redis | 0 |
| Cola `radar.business-events` / DLQ al final | 0 / 0 |

Resultado: **PR-05 aprobada**.

## 6. Limitaciones

- **US-11 y US-12:** los enunciados son los asumidos arriba; hay que confirmarlos con el informe completo.
- **QA-2 (95 % de las respuestas en ≤ 2 s):** no se midió con carga; las pruebas verifican el funcionamiento, no el
  rendimiento.
- **Servicios reales:** no se probó TLS contra Upstash ni CloudAMQP, ni la integración con Business, IAM y Kong.
- **Capturas:** las listadas en la sección 3 hay que tomarlas con el servicio levantado; este documento no las incluye.
