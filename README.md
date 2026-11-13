# radar-service

**Geo-Radar Engine Service (GRES)** de Foodly. Indexa la ubicación de cada huarique con celdas hexagonales
[H3](https://h3geo.org) y responde qué huariques **activos** hay cerca de un explorador, ordenados por distancia.

- Java 17, Spring Boot 3.5, Maven.
- Redis (hashes y sets por celda) como almacén, RabbitMQ para recibir los eventos de Business.
- La distancia se calcula con las coordenadas (haversine). El servicio **no** calcula rutas ni tiempos de llegada.

Los nombres de clases, endpoints, claves y eventos siguen el informe. Lo que el informe no define está en
[`docs/SPEC_RADAR_MESSAGING.md`](docs/SPEC_RADAR_MESSAGING.md) y cada diferencia con el informe, en
[`DEVIATIONS.md`](DEVIATIONS.md). [`docs/ALIGNMENT.md`](docs/ALIGNMENT.md) resume lo que el informe define por capa.

## Levantar todo con Docker

Requisitos: Docker con Compose v2. Los scripts de `scripts/` son de Bash (en Windows, Git Bash o WSL).

```bash
docker compose -f docker-compose.local.yml up -d --build --wait
```

Levanta Redis, RabbitMQ (con consola de administración) y Radar en la red `foodly-network`. Con `--wait`, el comando
termina cuando los tres están sanos. Los puertos se publican solo en `127.0.0.1`.

| Servicio   | URL / puerto              | Notas                                   |
|------------|---------------------------|-----------------------------------------|
| Radar      | http://localhost:8080     | API, Swagger y Actuator                 |
| RabbitMQ   | `localhost:5672`          | AMQP                                    |
| RabbitMQ UI| http://localhost:15672    | usuario `guest`, contraseña `guest`     |
| Redis      | `localhost:6379`          |                                         |

Comprobar que está vivo:

```bash
curl http://localhost:8080/radar/status      # {"status":"UP"}
```

Para detener todo: `docker compose -f docker-compose.local.yml down` (con `-v` si además quiere borrar los volúmenes).

### Cargar datos de ejemplo

```bash
scripts/seed-events.sh
```

Publica 20 eventos `HuariqueRegistered` (5 huariques en cada uno de Miraflores, Barranco, Surquillo y Lince; unos
cuantos inactivos) y espera a que Radar los indexe. Se puede ejecutar varias veces: los repetidos se descartan.
Si los huariques ya tienen una versión mayor (por ejemplo, después de la prueba PR-05), use
`scripts/seed-events.sh --reset`, que borra antes los huariques de prueba (`seed-*`) y los vuelve a sembrar.

### Probar una búsqueda

Los endpoints protegidos exigen un JWT de IAM con el rol `GASTRONOMIC_EXPLORER`. Para desarrollo, el script
`scripts/dev-token.sh` genera uno firmado con el secreto local:

```bash
TOKEN=$(scripts/dev-token.sh)

curl -s -X POST http://localhost:8080/radar/search \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{ "latitude": -12.1219, "longitude": -77.0297, "kRing": 3 }'
```

Respuesta con los datos de ejemplo (huariques **activos** de la zona, del más cercano al más lejano;
`distanceMeters` en metros). Los huariques inactivos no aparecen:

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

- Sin token responde **401**; con un token de `HUARIQUE_OWNER`, **403**.
- Coordenadas fuera de rango o `kRing` fuera de 0 a 5 responden **400** con
  `No se pudo procesar la consulta del radar: <detalle>`.
- Una zona sin huariques responde **200** con `{ "huariques": [], "total": 0 }`.

### Ver Swagger

Con Radar levantado, abra **http://localhost:8080/swagger-ui.html**. Para probar `POST /radar/search`:

1. Genere un token con `scripts/dev-token.sh`.
2. Pulse **Authorize**, pegue el token (sin la palabra `Bearer`) y confirme.
3. Abra `POST /radar/search`, pulse **Try it out** y ejecute el ejemplo.

La especificación OpenAPI en JSON está en http://localhost:8080/v3/api-docs.

### Prueba PR-05: Radar caído con eventos pendientes

```bash
scripts/pr05-consumer-down.sh
```

Indexa 20 huariques, detiene Radar, publica 20 eventos con Radar caído, lo vuelve a levantar y verifica que se
aplicaron 20 de 20; republica los mismos 20 y verifica que Redis no cambió (0 duplicados) y que la cola y la DLQ
quedan en 0. Imprime la hora de la falla, la de la recuperación y las métricas, y termina con código 0 si todo pasa.
Borra solo los huariques de prueba (prefijo `seed-`) y vacía la cola de Radar al empezar.

## Endpoints

| Método | Ruta                | Acceso                      | Descripción                                                            |
|--------|---------------------|-----------------------------|------------------------------------------------------------------------|
| POST   | `/radar/search`     | JWT, rol `GASTRONOMIC_EXPLORER` | Huariques activos de la zona de cobertura, ordenados por distancia |
| GET    | `/radar/status`     | Público                     | `200 {"status":"UP"}` si Radar puede atender búsquedas; `503 {"status":"DOWN"}` si no (Redis caído) |
| GET    | `/actuator/health`  | Público                     | Salud de Redis y RabbitMQ (la usa el `HEALTHCHECK` de Docker)          |
| GET    | `/swagger-ui.html`  | Público                     | Documentación interactiva                                              |
| GET    | `/v3/api-docs`      | Público                     | Especificación OpenAPI                                                 |

Todos los errores tienen el mismo formato: `{ status, error, message, path, timestamp }`, con el mensaje en español.

## Variables de entorno

Toda la configuración se lee de variables de entorno. En `docker-compose.local.yml` ya vienen definidas.

| Variable                          | Obligatoria | Por defecto | Descripción                                                                    |
|-----------------------------------|-------------|-------------|--------------------------------------------------------------------------------|
| `REDIS_URL`                       | Sí          | —           | `redis://host:6379` o `rediss://usuario:clave@host:6379` (TLS, Upstash)        |
| `RABBITMQ_URL`                    | Sí          | —           | `amqp://usuario:clave@host:5672/vhost` o `amqps://...` (TLS, CloudAMQP)        |
| `JWT_SECRET`                      | Sí          | —           | Secreto con el que IAM firma el JWT (HMAC). Mínimo 32 bytes, se usa como texto UTF-8 |
| `JWT_USER_ID_CLAIM`               | No          | `sub`       | Claim con el id de usuario                                                     |
| `JWT_ROLE_CLAIM`                  | No          | `role`      | Claim con el rol                                                               |
| `RADAR_H3_RESOLUTION`             | No          | `9`         | Resolución H3 fija (0 a 15)                                                    |
| `RADAR_MAX_K_RING`                | No          | `5`         | Máximo de anillos permitido en una búsqueda                                    |
| `RADAR_EVENTS_MAX_ATTEMPTS`       | No          | `3`         | Intentos al procesar un evento antes de enviarlo a la DLQ                      |
| `RADAR_EVENTS_INITIAL_BACKOFF_MS` | No          | `200`       | Espera antes del primer reintento                                              |
| `RADAR_EVENTS_BACKOFF_MULTIPLIER` | No          | `2.0`       | Factor por el que crece la espera en cada reintento                            |
| `REDIS_COMMAND_TIMEOUT_MS`        | No          | `2000`      | Tiempo máximo de cada comando de Redis                                         |
| `SERVER_PORT`                     | No          | `8080`      | Puerto HTTP                                                                    |

Con el perfil `local` (`SPRING_PROFILES_ACTIVE=local`), `REDIS_URL`, `RABBITMQ_URL` y `JWT_SECRET` tienen valores de
desarrollo (`redis://localhost:6379`, `amqp://guest:guest@localhost:5672` y un secreto fijo). **No use el perfil
`local` ni ese secreto fuera de su máquina.**

Variables solo del compose local: `RADAR_HOST_PORT` (8080), `REDIS_HOST_PORT` (6379), `RABBITMQ_HOST_PORT` (5672) y
`RABBITMQ_MANAGEMENT_HOST_PORT` (15672), para cambiar los puertos publicados en su equipo.

## Eventos de Business

Radar consume los eventos de Business desde RabbitMQ; no publica ninguno. Los nombres son los acordados en la spec
(a confirmar con Business):

| Elemento      | Nombre                                                                                                           |
|---------------|------------------------------------------------------------------------------------------------------------------|
| Exchange      | `foodly.business.events` (topic)                                                                                 |
| Cola          | `radar.business-events` (durable)                                                                                |
| Routing keys  | `huarique.registered`, `huarique.updated`, `huarique.location-updated`, `huarique.opening-status-changed`        |
| Dead letter   | exchange `foodly.dlx`, cola `radar.business-events.dlq`                                                          |

Formato del mensaje (JSON):

```json
{
  "eventId": "9f1c...",
  "eventType": "LocationUpdated",
  "huariqueId": "65f1c2a9e4b0a1d2c3f4a5b6",
  "version": 7,
  "occurredAt": "2026-11-13T12:00:00Z",
  "payload": { "latitude": -12.1498, "longitude": -77.0207, "status": "ACTIVE" }
}
```

- `eventType` es uno de `HuariqueRegistered`, `HuariqueUpdated`, `LocationUpdated` y `OpeningStatusChanged`.
  Cualquier otro (por ejemplo `MenuUpdated`) se confirma y se ignora.
- `payload` lleva `latitude` y `longitude` (salvo en `OpeningStatusChanged`) y `status` (`ACTIVE` o `INACTIVE`) o
  `isOpen`.
- Si la `version` es menor o igual a la guardada para ese huarique, el evento se descarta (reenvíos y mensajes
  desordenados no tienen efecto).
- El mensaje se confirma (ack) solo después de actualizar Redis. Si el procesamiento falla 3 veces, o el mensaje es
  ilegible, va a la DLQ. Si Radar está caído, los mensajes esperan en la cola.

## Desarrollo

```bash
./mvnw test        # en Windows: mvnw.cmd test
```

Las pruebas de integración usan Testcontainers (Redis y RabbitMQ) y se omiten si no hay Docker.

Para ejecutar Radar desde el IDE o con Maven, levante solo las dependencias y use el perfil `local`:

```bash
docker compose -f docker-compose.local.yml up -d --wait redis rabbitmq
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

### Estructura

```
src/main/java/pe/foodly/radar/
  domain/           modelo (HuariqueLocation, objetos de valor, eventos), repositorio y servicios; sin Spring
  application/      commands, queries y sus handlers; handlers de los eventos de Business
  interfaces/rest/  controllers, resources y manejo de errores
  infrastructure/   Redis, H3, RabbitMQ, seguridad (JWT) y configuración
```
