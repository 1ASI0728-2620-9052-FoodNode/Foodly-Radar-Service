# Foodly: especificación de implementación de Radar y Messaging

> **Fuente de verdad:** `docs/informe/informe-radar-messaging.md`. Es un extracto literal del informe TP corregido (13/11/2026) con las secciones 4.1.4, 4.2.5, 4.3.4, 5.1.4, 5.2, 5.3.2, 5.3.4, 5.5 y 5.6, más el contenido de los diagramas de clases y de base de datos.
> **Regla de oro:** los nombres de clases, métodos, endpoints, tablas, claves y eventos son los del informe. Este archivo solo completa lo que el informe no define, en los puntos marcados **[Decisión de implementación]**. Cualquier diferencia con el informe se anota en `DEVIATIONS.md`, para corregir el informe después.

---

## 1. Contexto común

- Seis Bounded Contexts (IAM, Business, Radar, Community, Reservation y Messaging), cada uno en su propio contenedor (DD-01) y con su propia base de datos (DD-02).
- Stack: **Java 17 + Spring Boot 3.x + Maven**. Docker Compose en un solo host (DD-10). Bases, broker y caché son servicios administrados en capa gratuita: Redis en Upstash, MySQL en Aiven, RabbitMQ en CloudAMQP.
- **Kong** es el punto de entrada único y valida el JWT en el borde. Además, **cada servicio vuelve a validarlo** (DD-06, QA-1).
- **JWT de IAM** (5.1.4): HMAC con clave compartida entre servicios, claims con el **id de usuario** y el **rol**, y expiración de 60 min. Roles: `GASTRONOMIC_EXPLORER` y `HUARIQUE_OWNER`.
  - **[Decisión de implementación]** Variables: `JWT_SECRET`, `JWT_ROLE_CLAIM` (por defecto `role`), `JWT_USER_ID_CLAIM` (por defecto `sub`). El informe no da los nombres. **Confirmar los nombres de los claims con quien implementa IAM.**
    - **[Decisión de implementación]** `JWT_SECRET` se usa como texto UTF-8 (no Base64) y debe tener al menos 32 bytes; el algoritmo HMAC (HS256, HS384 o HS512) lo determina el tamaño de la clave. El claim de rol puede ser un texto o una lista. **Confirmar con quien implementa IAM cómo deriva la clave de firma a partir del secreto.**
- Comunicación (DD-03):
  - REST síncrono: Messaging → Business, Radar y Reservation, **propagando el JWT del explorador**.
  - Eventos en RabbitMQ: Business → Radar y Community.
  - **Messaging no consume eventos.**
- Las rutas del servicio llevan su prefijo completo (`/radar/...`, `/messaging/...`), igual que en el informe.
- Fuera de alcance: rutas, tiempos de llegada, check-in, vouchers, aforo en vivo y delivery. H3 no calcula rutas ni garantiza mayor precisión (DD-05).

### Convenciones técnicas para los dos servicios [Decisión de implementación]
- Paquetes por capa: `domain`, `application`, `interfaces`, `infrastructure`. El dominio no depende de Spring, Redis, JPA, RabbitMQ, HTTP ni H3.
- Documentación con springdoc-openapi (`/swagger-ui.html`). Salud con Spring Boot Actuator (`/actuator/health`).
- `@RestControllerAdvice` con un error uniforme `{ status, error, message, path, timestamp }`. Mensajes al usuario en español.
- Configuración solo por variables de entorno. Perfil `local` (dependencias en Docker Compose) y perfil `stub` (sin los otros microservicios).
- Pruebas: JUnit 5 y Mockito, `@WebMvcTest` y Testcontainers (se omiten si no hay Docker).

---

## 2. RADAR: Geo-Radar Engine Service (GRES)

### 2.1 Domain Layer (5.2.1 y diagrama 5.2.7.1)
- **`HuariqueLocation`** (Aggregate Root)
  - Atributos: `huariqueId: HuariqueId`, `coordinates: GeoCoordinates`, `h3Cell: H3Cell`, `status: AvailabilityStatus`.
  - Métodos: `updateLocation(coordinates, h3Cell)`, `changeStatus(status)`, `isActive()`.
- **Value Objects**
  - `HuariqueId(value: String)`.
  - `GeoCoordinates(latitude, longitude)`: valida lat −90..90 y lng −180..180. Tiene `distanceTo(other): Distance` (haversine).
  - `H3Cell(index: String)`.
  - `CoverageZone(cells: Set<H3Cell>)` con `contains(cell)`.
  - `Distance(meters: double)`.
- **Enum** `AvailabilityStatus`: ACTIVE, INACTIVE.
- **Domain Events:** `H3CellCalculated`, `NearbyHuariquesFound`, `NoHuariquesFound`.
  - **[Decisión de implementación]** Son eventos internos del proceso (registrados por el servicio de dominio y despachados con `ApplicationEventPublisher` desde la capa de aplicación). Solo se registran en el log y en métricas; **no se publican en RabbitMQ**.
- **`IHuariqueLocationRepository`:** `save(location)` (upsert), `remove(id)`, `findById(id)`, `findByCells(cells): List<HuariqueLocation>`.
- **`IH3IndexService`** (puerto): `toCell(coordinates): H3Cell` y `kRing(cell, k): CoverageZone`.
- **`RadarSearchService.search(coordinates, kRing): List<HuariqueLocation>`** calcula la celda y la zona de cobertura, obtiene los candidatos, deja solo los ACTIVE, calcula la distancia y ordena de forma ascendente. Si no hay ninguno, devuelve una lista vacía.
  - `search` devuelve `List<HuariqueLocation>`, tal como el diagrama. La capa de aplicación calcula la distancia de cada resultado con `coordinates.distanceTo(...)` para armar la respuesta.
- Reglas: coordenadas válidas; solo activos dentro de la zona; orden por distancia ascendente; zona vacía → lista vacía; el upsert es idempotente.

### 2.2 Application Layer (5.2.3)
- **Commands y handlers**
  - `UpsertHuariqueLocationCommand(huariqueId, latitude, longitude, status)` → `UpsertHuariqueLocationCommandHandler`: recalcula la celda y guarda. Si la celda cambió, el repositorio mueve el huarique de un set al otro.
  - `ChangeHuariqueAvailabilityCommand(huariqueId, status)` → `ChangeHuariqueAvailabilityCommandHandler`.
  - `RemoveHuariqueLocationCommand(huariqueId)` → `RemoveHuariqueLocationCommandHandler`.
    - **[Decisión de implementación]** Se implementa el comando con su handler, pero **ningún evento lo dispara todavía**. **Pendiente:** el informe debe definir el evento que lo dispara (por ejemplo, `HuariqueDeactivated`) o quitar el comando. Anotado en `DEVIATIONS.md`.
- **Queries**
  - `SearchNearbyHuariquesQuery(latitude, longitude, kRing)` → `SearchNearbyHuariquesQueryHandler`.
  - `GetRadarStatusQueryHandler`.
- **Event Handlers** (integración con Business)
  - `HuariqueRegisteredEventHandler`, `HuariqueUpdatedEventHandler` y `LocationUpdatedEventHandler` → `UpsertHuariqueLocationCommand`.
  - `OpeningStatusChangedEventHandler` → `ChangeHuariqueAvailabilityCommand`.
  - **[Decisión de implementación]** `MenuUpdated` no le interesa a Radar: la cola de Radar **no** se enlaza a ese evento. Cualquier tipo de evento desconocido que llegue se confirma (ack) y se registra en el log con nivel debug.

### 2.3 Interface Layer (5.2.2)
- **`RadarController`:** `POST /radar/search`
  - Body `RadarSearchResource { latitude, longitude, kRing }`.
  - Respuesta 200 `RadarSearchResultResource { huariques: [HuariqueLocationResource { huariqueId, latitude, longitude, distanceMeters }], total }`.
- **`RadarStatusController`:** `GET /radar/status` → 200 `RadarStatusResource { status }`, apoyado en Actuator.
  - **[Decisión de implementación]** Radar está operativo (`UP`) cuando Redis responde, porque eso es lo que necesita para atender búsquedas; no depende del broker. Si no, responde **503** con `{ "status": "DOWN" }`. El informe solo dice «responde 200 si el servicio está activo».
- **Validaciones**
  - Coordenadas fuera de rango o k-ring inválido → 400 con un mensaje que diga que no se pudo procesar la consulta.
  - Zona sin huariques → 200 con lista vacía.
  - Sin token → 401.
- **[Decisión de implementación]** Límites de k-ring: 0 a `RADAR_MAX_K_RING` (por defecto 5) y resolución fija `RADAR_H3_RESOLUTION` (por defecto 9). El informe no da números. Anotar los valores elegidos en `DEVIATIONS.md` para agregarlos al 5.2.
- **[Decisión de implementación]** **Validación del JWT y roles** (DD-06, QA-1 y 5.1.4: cada servicio valida el token, no solo Kong). Radar valida el JWT y exige el rol `GASTRONOMIC_EXPLORER` en `/radar/search` (también es el JWT que propaga Messaging). `/radar/status`, Actuator y Swagger son públicos. El informe 5.2.2 solo menciona el rechazo en el API Gateway y no nombra ningún rol.
- Los filtros de RF-12 (categoría, precio) **no** están en Radar según 5.2: el cliente los aplica con los datos de Business. No agregarlos sin decisión del equipo.

### 2.4 Infrastructure Layer (5.2.4 y diagrama 5.2.7.2)
- **`RedisHuariqueLocationRepository`** (Spring Data Redis / Lettuce)
  - Hash `radar:huarique:{huariqueId}` con `lat`, `lng`, `h3Cell`, `status`, `updatedAt` y **`version`**.
  - Set `radar:cell:{h3Index}` con los huariqueId de esa celda.
  - El cambio de celda (SREM del set viejo, SADD del nuevo, HSET del hash) es atómico, con MULTI/EXEC o un script Lua.
  - `findByCells` usa pipelining (QA-2: 95 % de las respuestas en ≤ 2 s).
  - El diagrama 5.2.7.2 no muestra `version`, pero 5.2.4 la exige. Anotarlo en `DEVIATIONS.md`.
- **`UberH3IndexService`** usa `com.uber:h3` v4 (`latLngToCell`, `gridDisk`).
- **`BusinessEventListener`** (`@RabbitListener`) y **`RabbitMQConfig`**
  - Cola durable propia, enlazada a `HuariqueRegistered`, `HuariqueUpdated`, `LocationUpdated` y `OpeningStatusChanged`, con su Dead Letter Queue.
  - Antes de aplicar un evento, compara su `version` con la del hash. Si es menor o igual, lo descarta y hace ack.
  - Hace **ack manual** solo después de actualizar Redis.
  - Si falla **3 veces**, hace `basicNack(requeue=false)` y el mensaje va a la DLQ.
  - Si Radar está caído, los mensajes esperan en la cola (QA-3, PR-05).
  - **[Decisión de implementación]** Detalles del consumo:
    - Los eventos se procesan de uno en uno y en orden de llegada (prefetch 1, un solo consumidor).
    - «Falla 3 veces» son 3 intentos en total, con espera de 200 ms que se duplica (`RADAR_EVENTS_MAX_ATTEMPTS`, `RADAR_EVENTS_INITIAL_BACKOFF_MS`, `RADAR_EVENTS_BACKOFF_MULTIPLIER`).
    - Un mensaje ilegible o con datos inválidos (campos obligatorios ausentes, `status` desconocido, coordenadas fuera de rango) **no se reintenta**: va directo a la DLQ. Si el hilo se interrumpe durante la espera (apagado), el mensaje queda sin confirmar y se reentrega.
    - El tipo de evento se toma del campo `eventType` del sobre, con los nombres del informe (`HuariqueRegistered`, `HuariqueUpdated`, `LocationUpdated`, `OpeningStatusChanged`), no de la routing key.
    - El estado sale de `payload.status` (`ACTIVE` o `INACTIVE`) o, si no viene, de `payload.isOpen` (`true` es `ACTIVE`). `HuariqueRegistered`, `HuariqueUpdated` y `LocationUpdated` deben traer `latitude` y `longitude`, porque ejecutan `UpsertHuariqueLocationCommand`. **Confirmar con Business.**
    - La versión se guarda con una escritura aparte después de que el handler actualiza Redis, y el ack va después de ambas. Con un solo consumidor es seguro: si Radar cae entre las dos escrituras, el mensaje se reentrega y el upsert idempotente lo repite.
    - `OpeningStatusChanged` de un huarique que aún no está indexado falla (puede haber llegado antes que `HuariqueRegistered`), se reintenta y, si sigue fallando, va a la DLQ.
    - La conexión se configura con `RABBITMQ_URL` en `spring.rabbitmq.addresses`. Con `amqps://` Spring Boot activa TLS con el almacén de confianza de la JVM y verifica el nombre del host.
  - **[Decisión de implementación, confirmar con Business]**
    - Exchange topic `foodly.business.events`.
    - Routing keys: `huarique.registered`, `huarique.updated`, `huarique.location-updated` y `huarique.opening-status-changed`.
    - Cola `radar.business-events`; DLX `foodly.dlx`; DLQ `radar.business-events.dlq`.
    - Envelope: `{ eventId, eventType, huariqueId, version, occurredAt, payload: { latitude, longitude, status|isOpen } }`.
- **`RedisConfig`** lee `REDIS_URL` (Upstash usa `rediss://`). **`RabbitMQConfig`** lee `RABBITMQ_URL` (CloudAMQP usa `amqps://`).
- **Pendiente (fuera de este sprint):** la reconciliación Business–Radar (DD-08) es una propuesta del informe y no se implementa ahora. Anotado en `DEVIATIONS.md`.

---

## 3. MESSAGING: Messaging Service (MS)

### 3.1 Domain Layer (5.6.1 y diagrama 5.6.7.1)
- **`Conversation`** (Aggregate Root)
  - Atributos: `id`, `explorerId`, `status`, `startedAt`, `lastMessageAt`, `messages`.
  - Métodos:
    - `addExplorerMessage(content): Message`
    - `addLiaReply(content, intent): Message`
    - `linkReservation(messageId, reservationId)`
    - `close()`
    - `isActive()`
  - Solo una conversación activa acepta mensajes y el historial va en orden cronológico.
  - En 5.6.1 el título todavía dice "Sesiones de chat"; en el código se llama `Conversation`.
- **`Message`** (Entity)
  - Atributos: `id`, `sender`, `content`, `intent`, `reservationId`, `sentAt`.
  - Métodos: `isFromLia()` y `hasReservation()`.
  - **[Decisión de implementación]** Se agrega `huariqueId` (opcional), porque la tabla `messages` tiene `huarique_id`.
- **Value Objects**
  - `MessageContent(text)` con `isBlank()` y `length()`. No puede estar vacío ni exceder la longitud máxima definida para el chat.
    - **[Decisión de implementación]** La longitud máxima es `MESSAGING_MAX_LENGTH` (por defecto 1000). El informe no da el número.
  - `UserIntent(type, cravings, budgetRange: PriceRange, location: GeoCoordinates, partySize, requestedAt, huariqueId)` con `isComplete()` y `missingContext()`.
    - **[Decisión de implementación]** `UserIntent` incluye `huariqueId: HuariqueId` (opcional), el «huarique referido». `IntentRoutingService` lo necesita para `QUERY_BUSINESS` y `FORWARD_TO_RESERVATION`, y el informe 5.6.1 no lo define. El informe debe agregar «huarique referido» a `UserIntent`.
  - `PriceRange` y `GeoCoordinates`.
    - **[Decisión de implementación]** `PriceRange = { min, max }` en soles: `BigDecimal`, `min <= max`, ambos `>= 0`. El informe solo nombra el tipo.
  - Identificadores: `ConversationId`, `MessageId`, `ExplorerId`, `HuariqueId`, `ReservationId`.
- **Enums:** `SenderType` (EXPLORER, LIA), `ConversationStatus` (ACTIVE, CLOSED), `IntentType` (RECOMMENDATION_REQUEST, HUARIQUE_INQUIRY, RESERVATION_REQUEST, UNKNOWN) y `RoutingDecision` (RECOMMEND_VIA_RADAR, QUERY_BUSINESS, FORWARD_TO_RESERVATION, ASK_CLARIFICATION).
- **`IConversationRepository`:** `save`, `findById`, `findActiveByExplorerId` y `findAllByExplorerId`.
- **`ILiaAgentGateway`** (puerto ACL): `requestInterpretation(id, content, ctx): void`.
- **`IntentRoutingService.route(intent): RoutingDecision`**
  - RECOMMENDATION_REQUEST con ubicación → RECOMMEND_VIA_RADAR.
  - HUARIQUE_INQUIRY con `huariqueId` → QUERY_BUSINESS.
  - RESERVATION_REQUEST completa (`huariqueId`, `requestedAt`, `partySize`) → FORWARD_TO_RESERVATION. Messaging no valida horario ni capacidad.
  - Cualquier otro caso → ASK_CLARIFICATION, usando `missingContext()`.

### 3.2 Application Layer (5.6.3)
- **Commands**
  - `SendMessageCommand(explorerId, conversationId?, content, location?)`.
  - `RegisterLiaReplyCommand(conversationId, inReplyToMessageId, replyContent, intent, suggestedHuariques, requestedReservation?)`.
    - **[Decisión de implementación]** Se agrega `inReplyToMessageId` (**obligatorio**): el id del mensaje del explorador al que responde Lía. Se usa para la idempotencia y como clave del `ExplorerTokenCache`. El informe 5.6.3 no lo define.
  - **[Decisión de implementación]** `RegisterLiaTimeoutCommand(conversationId, inReplyToMessageId)` → `RegisterLiaTimeoutCommandHandler`. Lo ejecuta `LiaTimeoutJob` (ver 3.4) y persiste el aviso de reserva manual (RF-15). El informe 5.6.3 solo define dos commands.
- **`SendMessageCommandHandler`:** obtiene o crea la conversación activa, registra el mensaje, persiste y llama a `ILiaAgentGateway`.
  - **[Decisión de implementación]** La llamada al gateway se hace **después del commit** y de forma **asíncrona**, para que el endpoint responda 202 sin esperar.
- **`RegisterLiaReplyCommandHandler`:** decide con `IntentRoutingService` y ejecuta:
  - **Radar:** pide los huariques cercanos con la ubicación del intent.
  - **Business:** pide detalle, menú, precio u horario. En una recomendación, también trae el nombre de los primeros resultados de Radar.
  - **Reservation:** reenvía la solicitud, guarda el `reservationId` con `linkReservation` y luego registra la respuesta de Lía.
  - Los `suggestedHuariques` del callback son opcionales; **los datos de Radar y Business mandan**.
  - **[Decisión de implementación]** Es idempotente: si ya existe una respuesta de Lía para `inReplyToMessageId` (incluido el aviso de timeout), se ignora el callback con un log `warn` y se responde 202 igual. **No se reemplaza el aviso**; el explorador puede volver a escribir. La correlación se guarda dentro de `intent_data`.
  - **[Decisión de implementación]** El ruteo se procesa de forma asíncrona: el callback responde 202 sin esperar las llamadas a Radar, Business y Reservation (ver 3.3).
- **Query handlers:** `GetConversationsByExplorerQueryHandler` y `GetMessagesByConversationQueryHandler` (404 si la conversación no existe o es de otro explorador).
- **Outbound Ports:** `IBusinessClient`, `IRadarClient` e `IReservationClient`.
- No hay event handlers.

### 3.3 Interface Layer (5.6.2)
- **`LiaChatController`:** `POST /messaging/chat` con `SendMessageResource { content, conversationId?, latitude?, longitude? }` → **202**.
  - **[Decisión de implementación]** Body de respuesta: `{ conversationId, messageId, status: "PROCESSING" }`.
- **`ConversationController`**
  - `GET /messaging/conversations/me` → `ConversationResource[]`.
  - `GET /messaging/conversations/{conversationId}/messages` → `MessageResource[]` con id, emisor, contenido, intención, reserva y fecha.
- **`N8nCallbackController`:** `POST /messaging/callbacks/n8n` con `LiaCallbackResource { conversationId, inReplyToMessageId, replyText, intent, suggestedHuariques[], requestedReservation? }`.
  - **[Decisión de implementación]** `inReplyToMessageId` es **obligatorio** y coincide con el JSON de 3.5. El controlador convierte `LiaCallbackResource` en `RegisterLiaReplyCommand` (ver 3.4, `N8nLiaAgentGateway`).
  - Lo llama el **Integration System**, no n8n directamente.
  - Se protege con un secreto compartido entre el Integration System y Messaging. No usa JWT.
    - **[Decisión de implementación]** El secreto va en el header `X-Callback-Secret` (variable `INTEGRATION_CALLBACK_SECRET`) y se compara en tiempo constante. El informe no nombra el header ni la variable.
  - **[Decisión de implementación]** Respuestas del callback: **202** con `{ "status": "RECEIVED" }`, y el ruteo se procesa de forma asíncrona (el Integration System no espera las llamadas a Radar, Business y Reservation). **401** si el secreto es incorrecto y **400** si el body es inválido.
- **Códigos de respuesta**
  - 202 al aceptar un mensaje y 200 en las consultas.
  - 400 si los datos son inválidos y 401 si no hay token o el secreto es incorrecto.
  - 403 si el rol no es `GASTRONOMIC_EXPLORER`.
  - 404 si la conversación no existe o es de otro explorador.
- **[Decisión de implementación, anotar en DEVIATIONS]** Cómo le llega al cliente la respuesta de Lía:
  - DD-04 elige STOMP/WebSocket en lugar de polling, pero 5.6 no lo menciona.
  - Messaging publica cada respuesta de Lía en `/topic/conversations/{conversationId}`. El endpoint STOMP es `/messaging/ws` y valida el JWT al conectar.
  - `GET .../messages` sigue siendo la fuente de verdad y sirve de respaldo.

### 3.4 Infrastructure Layer (5.6.4 y diagrama 5.6.7.2)
- **`ConversationRepository`** (Spring Data JPA sobre MySQL, con entidades JPA separadas del dominio). Migración Flyway idéntica al diagrama:
  - `conversations`: `id CHAR(36) PK`, `explorer_id CHAR(36)`, `status VARCHAR(10)`, `started_at DATETIME`, `last_message_at DATETIME`.
  - `messages`: `id CHAR(36) PK`, `conversation_id CHAR(36) FK`, `sender VARCHAR(10)`, `content TEXT`, `intent_type VARCHAR(30) NULL`, `intent_data JSON NULL`, `huarique_id CHAR(36) NULL`, `reservation_id CHAR(36) NULL`, `sent_at DATETIME`.
  - Sin FK física para `explorer_id`, `huarique_id` ni `reservation_id`. No agregar columnas: los datos extra van en `intent_data`.
- **`N8nLiaAgentGateway`** (ACL): traduce al formato del webhook de n8n y lo invoca **a través del Integration System** (DD-07).
  - **[Decisión de implementación]** El gateway **solo envía la solicitud**. Quien convierte `LiaCallbackResource` en `RegisterLiaReplyCommand` es `N8nCallbackController` (Interface). El informe 5.6.4 dice que el gateway «convierte la respuesta recibida en `LiaCallbackResource`».
  - **[Decisión de implementación]** Modo configurable `LIA_GATEWAY_MODE`:
    - `integration-system` (por defecto);
    - `direct-n8n` (solo para desarrollo local);
    - `stub` (clasifica por palabras clave, sin IA).
  - **[Decisión de implementación]** **Timeout de Lía:** lo detecta `LiaTimeoutJob`, ubicado en `infrastructure/scheduling`.
    - Cada 10 s busca los mensajes `EXPLORER` con `sent_at` anterior a `now - LIA_TIMEOUT_SECONDS` que no tengan un mensaje `LIA` cuyo `intent_data.inReplyToMessageId` sea su id (`JSON_EXTRACT` en MySQL).
    - Por cada mensaje encontrado ejecuta `RegisterLiaTimeoutCommand` (con su handler, en Application). El handler registra el error en el log y **persiste una respuesta de Lía** que ofrece la **reserva manual (RF-15)** con `inReplyToMessageId`, para que el cliente la vea en el historial y por WebSocket.
    - La búsqueda con `JSON_EXTRACT` es una consulta nativa de infraestructura, en un repositorio JPA aparte que usa el job. **No se toca `IConversationRepository`.**
    - Antes de insertar el aviso, el handler vuelve a verificar que no exista respuesta para ese `inReplyToMessageId`.
    - Se asume una sola instancia (DD-10). Con varias instancias habría que usar un lock (por ejemplo, ShedLock).
    - El informe 5.6.4 solo dice que el servicio registra el error y que el frontend ofrece la reserva manual.
- **`BusinessClient`, `RadarClient` y `ReservationClient`** usan `RestClient` y **propagan el JWT del explorador**.
  - **[Decisión de implementación]** Timeout de 3 s en los clientes REST. El informe no da el valor.
  - **[Decisión de implementación]** El callback no trae el JWT del usuario. Por eso `SendMessageCommandHandler` guarda el token del explorador en un `ExplorerTokenCache` **en memoria**: clave = messageId (el mismo valor que `inReplyToMessageId` del callback), TTL = el menor entre la expiración del token y `LIA_TIMEOUT_SECONDS` + margen. El token **nunca se persiste ni se registra en logs**.
  - Si el token ya no está (reinicio o vencimiento), Lía responde que no pudo completar la acción y ofrece la reserva manual.
  - **[Decisión de implementación]** Endpoints que se consumen (configurables; confirmar con los dueños de cada contexto):
    - **Radar:** `POST {RADAR_URL}/radar/search`. El k-ring sale de `MESSAGING_RADAR_K_RING` (por defecto 2).
    - **Business:** `GET {BUSINESS_URL}/business-api/huariques/{id}` y `GET {BUSINESS_URL}/business-api/huariques/{id}/menu`. El informe solo dice que existen "operaciones de consulta", sin rutas.
    - **Reservation:** `POST {RESERVATION_URL}/reservation-api/reservations` con `{ huariqueId, date, time, partySize }`. Responde 201 y Reservation toma al explorador del JWT. Para consultar estado, `GET .../reservation-api/reservations/{id}` (configurable, a confirmar con Reservation), que el informe 5.5.2 no lista.
- **`JwtAuthenticationFilter`** valida el JWT en los endpoints de usuario. El callback usa un filtro aparte que solo revisa el secreto.

### 3.5 Contrato con el Integration System y n8n [Decisión de implementación, confirmar con quien hace Integration System]
Solicitud (Messaging → Integration System, `POST {INTEGRATION_SYSTEM_URL}{LIA_PATH}`):
```json
{ "conversationId": "…", "messageId": "…", "content": "quiero un ceviche barato cerca",
  "location": { "latitude": -12.12, "longitude": -77.03 },
  "history": [ { "sender": "EXPLORER", "content": "…" } ] }
```
Callback (Integration System → Messaging, `POST /messaging/callbacks/n8n`, header `X-Callback-Secret`):
```json
{ "conversationId": "…", "inReplyToMessageId": "…", "replyText": "¡Claro! Te busco opciones cerca…",
  "intent": { "type": "RECOMMENDATION_REQUEST", "cravings": "ceviche", "budgetRange": { "min": 0, "max": 25 },
              "location": { "latitude": -12.12, "longitude": -77.03 }, "partySize": null, "requestedAt": null,
              "huariqueId": null, "missingContext": [] },
  "suggestedHuariques": [], "requestedReservation": null }
```
n8n (con OpenAI) **interpreta y redacta**. Las consultas a Radar, Business y Reservation las hace Messaging.
Respuesta del callback: `202 { "status": "RECEIVED" }` (ver 3.3).

---

## 4. Inconsistencias del informe que conviene corregir (no bloquean el código)
1. 5.6.1: "Sesiones de chat" → `Conversation`.
2. 5.6.4: "reserva manual (RF-18)" → **RF-15** (RF-18 es el registro desde la Landing Page).
3. 5.2.2: "No hay huariques aquí aún (US-15)" → **US-12 / RF-13** (US-15 es la reserva).
4. 5.2.7.2: falta el campo `version` en el hash, aunque 5.2.4 lo usa.
5. DD-04 y 5.6.2: no se indica cómo le llega al cliente la respuesta de Lía.
6. 5.6.4: dice que `N8nLiaAgentGateway` «convierte la respuesta recibida en `LiaCallbackResource`». El gateway solo envía la solicitud; quien convierte `LiaCallbackResource` en `RegisterLiaReplyCommand` es `N8nCallbackController` (Interface).
7. 5.5.2: falta el endpoint de consulta de una reserva, aunque 5.5.4 dice que Messaging consulta su estado por REST. Se usa `GET /reservation-api/reservations/{id}`, configurable, a confirmar con Reservation.

Las referencias RF-13, RF-15, US-12, US-15 y QA-2 de esta lista están verificadas contra el informe completo.

### Pendientes del informe (registrados en `DEVIATIONS.md`)
- `RemoveHuariqueLocationCommand` no tiene evento que lo dispare: definir uno (por ejemplo, `HuariqueDeactivated`) o quitar el comando.
- La reconciliación Business–Radar (DD-08) es una propuesta del informe y queda fuera de este sprint.
