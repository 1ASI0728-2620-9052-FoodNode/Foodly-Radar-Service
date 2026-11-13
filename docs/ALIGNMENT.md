# ALIGNMENT: lo que define el informe para Radar y Messaging

> **Fuente:** `docs/informe/informe-radar-messaging.md` (extracto del informe TP, 13/11/2026). Los nombres del informe mandan.
> **Alcance:** todo lo que el informe define para **Radar (GRES)** y **Messaging (MS)**, organizado por capa. Aquí solo hay lo que dice el informe; las decisiones que lo completan están en `docs/SPEC_RADAR_MESSAGING.md` y las diferencias en `DEVIATIONS.md`.
> **Convención:** las secciones se citan como en el informe. «Diagrama» = contenido de los diagramas copiado en 5.2.7.1, 5.2.7.2, 5.6.7.1 y 5.6.7.2 del extracto.

---

# PARTE A. RADAR: Geo-Radar Engine Service (GRES)

## A.1 Domain Layer

### Aggregate Root, Value Objects y Enums

| Elemento | Tipo | Atributos y métodos que define el informe | Sección del informe |
|---|---|---|---|
| `HuariqueLocation` | Aggregate Root | Atributos: `huariqueId: HuariqueId`, `coordinates: GeoCoordinates`, `h3Cell: H3Cell`, `status: AvailabilityStatus`. Métodos: `updateLocation(coordinates, h3Cell)`, `changeStatus(status)`, `isActive(): boolean`. Si cambian las coordenadas, recalcula su celda H3. Solo los activos pueden aparecer en resultados. | 5.2.1; 5.2.7.1 |
| `HuariqueId` | Value Object | `value: String`. Identificador emitido por Business. | 5.2.1; 5.2.7.1 |
| `GeoCoordinates` | Value Object | `latitude: double`, `longitude: double`. Método `distanceTo(other): Distance`. Valida latitud entre -90 y 90 y longitud entre -180 y 180. | 5.2.1; 5.2.7.1 |
| `H3Cell` | Value Object | `index: String`. Celda hexagonal calculada con una resolución fija. | 5.2.1; 5.2.7.1 |
| `CoverageZone` | Value Object | `cells: Set<H3Cell>`. Método `contains(cell): boolean`. Es la celda central más el k-ring. | 5.2.1; 5.2.7.1 |
| `Distance` | Value Object | `meters: double`. Distancia en metros entre el explorador y un huarique. | 5.2.1; 5.2.7.1 |
| `AvailabilityStatus` | Enum | `ACTIVE`, `INACTIVE`. | 5.2.1; 5.2.7.1 |

### Domain Events

| Evento | Cuándo se emite | Sección del informe |
|---|---|---|
| `H3CellCalculated` | Cuando se calcula la celda H3 del explorador. | 5.2.1; 5.2.7.1 |
| `NearbyHuariquesFound` | Cuando la búsqueda devuelve al menos un huarique. | 5.2.1; 5.2.7.1 |
| `NoHuariquesFound` | Cuando la zona de cobertura no tiene huariques. | 5.2.1; 5.2.7.1 |

El diagrama indica que `RadarSearchService` los **publica** (5.2.7.1).

### Repository Interface, Domain Services y puertos

| Elemento | Tipo | Operaciones que define el informe | Sección del informe |
|---|---|---|---|
| `IHuariqueLocationRepository` | Repository Interface | `save(location)` (upsert), `remove(id)`, `findById(id)`, `findByCells(cells): List<HuariqueLocation>`. No depende de Redis. | 5.2.1; 5.2.7.1 |
| `IH3IndexService` | Puerto del dominio | `toCell(coordinates): H3Cell`, `kRing(cell, k): CoverageZone`. El dominio no depende de Uber H3. | 5.2.1; 5.2.7.1 |
| `RadarSearchService` | Domain Service | `search(coordinates, kRing): List<HuariqueLocation>`. Calcula la zona de cobertura, obtiene candidatos de las celdas, calcula la distancia a cada uno y ordena de menor a mayor. | 5.2.1; 5.2.7.1 |

### Business Rules

| Regla | Sección del informe |
|---|---|
| Las coordenadas deben ser válidas. | 5.2.1 |
| Solo se devuelven huariques activos dentro de la zona de cobertura. | 5.2.1 |
| Los resultados se ordenan por distancia ascendente. | 5.2.1 |
| Si no hay establecimientos cercanos, se devuelve una lista vacía. | 5.2.1 |
| La actualización de una ubicación es idempotente: reemplaza el registro anterior del mismo huarique. | 5.2.1 |

---

## A.2 Application Layer

| Elemento | Tipo | Qué define el informe | Sección del informe |
|---|---|---|---|
| `UpsertHuariqueLocationCommand` | Command | `huariqueId`, latitud, longitud y estado. Se usa al registrar un huarique o cambiar su ubicación. | 5.2.3 |
| `ChangeHuariqueAvailabilityCommand` | Command | `huariqueId` y nuevo estado. | 5.2.3 |
| `RemoveHuariqueLocationCommand` | Command | `huariqueId`. Elimina el huarique del índice. | 5.2.3 |
| `UpsertHuariqueLocationCommandHandler` | Command Handler | Crea o actualiza `HuariqueLocation`, recalcula su celda H3 y lo guarda con `IHuariqueLocationRepository`. Si la celda cambió, el repositorio lo mueve del índice anterior al nuevo. | 5.2.3 |
| `ChangeHuariqueAvailabilityCommandHandler` | Command Handler | Actualiza el estado del agregado. | 5.2.3 |
| `RemoveHuariqueLocationCommandHandler` | Command Handler | Elimina la ubicación indexada. | 5.2.3 |
| `SearchNearbyHuariquesQuery` | Query | Latitud, longitud y k-ring. | 5.2.3 |
| `SearchNearbyHuariquesQueryHandler` | Query Handler | Crea las coordenadas, calcula la celda H3 y la zona de cobertura, y consulta los candidatos con `RadarSearchService`. Devuelve los resultados ordenados por distancia. | 5.2.3 |
| `GetRadarStatusQueryHandler` | Query Handler | Devuelve el estado operativo del servicio. | 5.2.3 |
| `HuariqueRegisteredEventHandler` | Event Handler | Ejecuta `UpsertHuariqueLocationCommand`. | 5.2.3 |
| `HuariqueUpdatedEventHandler` | Event Handler | Ejecuta `UpsertHuariqueLocationCommand`. | 5.2.3 |
| `LocationUpdatedEventHandler` | Event Handler | Ejecuta `UpsertHuariqueLocationCommand` con las nuevas coordenadas. | 5.2.3 |
| `OpeningStatusChangedEventHandler` | Event Handler | Ejecuta `ChangeHuariqueAvailabilityCommand`. | 5.2.3 |

La capa no contiene lógica de persistencia (5.2.3).

---

## A.3 Interface Layer

### Controllers y endpoints

| Elemento | Tipo | Qué define el informe | Sección del informe |
|---|---|---|---|
| `RadarController` | Controller | Gestiona la búsqueda de huariques cercanos. | 5.2.2 |
| `POST /radar/search` | Endpoint | Recibe latitud, longitud y k-ring. Devuelve los huariques de la zona de cobertura ordenados por distancia. | 5.2.2 |
| `RadarStatusController` | Controller | Permite verificar que el servicio está operativo. | 5.2.2 |
| `GET /radar/status` | Endpoint | Responde 200 si el servicio está activo. Se apoya en Spring Boot Actuator. | 5.2.2; 5.2.4 |

### Resources

| Resource | Contenido que define el informe | Sección del informe |
|---|---|---|
| `RadarSearchResource` | Latitud, longitud y k-ring de la búsqueda. | 5.2.2 |
| `RadarSearchResultResource` | Lista de huariques encontrados y total de resultados. | 5.2.2 |
| `HuariqueLocationResource` | Identificador, coordenadas y distancia en metros de cada huarique. | 5.2.2 |
| `RadarStatusResource` | Estado del servicio. | 5.2.2 |

### Validaciones y respuestas

| Caso | Respuesta | Sección del informe |
|---|---|---|
| Solicitud válida | HTTP 200 con la lista ordenada. | 5.2.2 |
| Coordenadas fuera de rango o k-ring inválido | HTTP 400 con un mensaje que indica que no se pudo procesar la consulta. | 5.2.2 |
| Zona sin huariques | HTTP 200 con lista vacía. El frontend muestra «No hay huariques aquí aún» (el informe cita US-15). | 5.2.2 |
| Sin token válido | 401, rechazado por el API Gateway antes de llegar al servicio. | 5.2.2 |

---

## A.4 Infrastructure Layer

### Componentes

| Elemento | Tipo | Qué define el informe | Sección del informe |
|---|---|---|---|
| `RedisHuariqueLocationRepository` | Repository | Implementa `IHuariqueLocationRepository` con Spring Data Redis (Lettuce). Cada huarique es un hash; un set por celda H3 contiene los identificadores de esa celda. La búsqueda consulta los sets de todas las celdas del k-ring, sin comparar contra todos los huariques (QA-2). | 5.2.4 |
| `UberH3IndexService` | H3 Adapter | Implementa `IH3IndexService` con la librería Uber H3. Calcula la celda de unas coordenadas y su k-ring. | 5.2.4 |
| `BusinessEventListener` (`@RabbitListener`) | Event Integration | Escucha una cola durable propia del contexto y deriva cada evento a su handler. | 5.2.4 |
| `RabbitMQConfig` | Configuración | Declara el binding de la cola de Radar a `HuariqueRegistered`, `HuariqueUpdated`, `LocationUpdated` y `OpeningStatusChanged`. Declara una Dead Letter Queue. Toma la URL de CloudAMQP de una variable de entorno. | 5.2.4 |
| `RedisConfig` | Configuración | Toma la URL de Upstash de una variable de entorno. | 5.2.4 |
| Spring Boot Actuator | Salud | Base de `GET /radar/status`. | 5.2.4 |
| Persistencia en Redis (Upstash, serverless) | Persistence | Base propia del contexto. Se sincroniza con los eventos de Business y se puede reconstruir a partir de ellos. | 5.2.4; 4.3.4 |

### Claves Redis

| Clave | Tipo | Contenido | Sección del informe |
|---|---|---|---|
| `radar:cell:{h3Index}` | Set | Miembros: `huariqueId`. | 5.2.7.2 |
| `radar:huarique:{huariqueId}` | Hash | `lat: double`, `lng: double`, `h3Cell: string`, `status: ACTIVE / INACTIVE`, `updatedAt: timestamp`. | 5.2.7.2 |
| `version` (campo del hash) | Campo | El diagrama no lo muestra, pero el listener compara la `version` del evento con la «almacenada en el hash del huarique». | 5.2.4 (ausente en 5.2.7.2) |

### Eventos que consume Radar (RabbitMQ, CloudAMQP)

| Evento | Origen | Efecto en Radar | Sección del informe |
|---|---|---|---|
| `HuariqueRegistered` | Business (se produce al registrar un establecimiento) | `UpsertHuariqueLocationCommand` | 5.3.4; 4.2.5; 5.2.3 |
| `HuariqueUpdated` | Business (se produce al modificar los datos del establecimiento) | `UpsertHuariqueLocationCommand` | 5.3.4; 4.2.5; 5.2.3 |
| `LocationUpdated` | Business (el dueño modifica las coordenadas; Radar recalcula la celda H3) | `UpsertHuariqueLocationCommand` con las nuevas coordenadas | 5.3.4; 4.2.5; 5.2.3 |
| `OpeningStatusChanged` | Business (se modifica el estado operativo) | `ChangeHuariqueAvailabilityCommand` | 5.3.4; 4.2.5; 5.2.3 |

El informe define `MenuUpdated` en Business (5.3.4), pero no figura entre los eventos de Radar (4.2.5; 5.2.4).

Campos que Business incluye en cada evento: `eventId`, `huariqueId`, `version`, `occurredAt` (5.3.4, Transactional Outbox).

### Reglas de consumo

| Regla | Sección del informe |
|---|---|
| Cola durable con ack manual **después** de actualizar Redis. | 4.1.4; 5.2.4 |
| Si la `version` del evento es menor o igual a la del hash, se descarta y se confirma el mensaje. | 5.2.4 |
| Si el procesamiento falla tres veces, el mensaje pasa a la Dead Letter Queue. | 5.2.4 |
| Si Radar está caído, los eventos esperan en la cola persistente y se procesan al reiniciar (QA-3). | 5.2.4 |
| Un reenvío no genera duplicados porque el upsert es idempotente (SADD / HSET con la versión del evento). | 4.1.4; 5.2.4 |
| Reconciliación periódica Business–Radar: compara el catálogo activo con las celdas H3 y re-publica o reconstruye lo desincronizado (DD-08, propuesta). | 4.1.4 |

### Seguridad y despliegue

| Elemento | Qué define el informe | Sección del informe |
|---|---|---|
| JWT de IAM | HMAC con clave compartida, claims con id de usuario y rol, expiración 60 min, validado de forma independiente por cada servicio (QA-1). Radar figura entre los servicios que lo validan. | 5.1.4; 4.3.4 |
| Contenedor | Geo-Radar Engine Service (GRES), Spring Boot 3.x, Redis en Upstash. REST vía Kong y desde Messaging. | 4.3.4 |
| Decisiones | DD-01, DD-02, DD-03, DD-05 (H3 selecciona celdas; la distancia sale de las coordenadas; no calcula rutas), DD-06, DD-08, DD-10. | 4.1.4 |

---

# PARTE B. MESSAGING: Messaging Service (MS)

## B.1 Domain Layer

### Aggregate Root y Entity

| Elemento | Tipo | Atributos y métodos que define el informe | Sección del informe |
|---|---|---|---|
| `Conversation` | Aggregate Root | Atributos: `id: ConversationId`, `explorerId: ExplorerId`, `status: ConversationStatus`, `startedAt: LocalDateTime`, `lastMessageAt: LocalDateTime`, `messages: List<Message>`. Métodos: `addExplorerMessage(content): Message`, `addLiaReply(content, intent): Message`, `linkReservation(messageId, reservationId): void`, `close(): void`, `isActive(): boolean`. Solo una conversación activa acepta mensajes; el historial queda en orden cronológico. En 5.6.1 el texto la llama «Sesiones de chat». | 5.6.1; 5.6.7.1 |
| `Message` | Entity | Atributos: `id: MessageId`, `sender: SenderType`, `content: MessageContent`, `intent: UserIntent`, `reservationId: ReservationId`, `sentAt: LocalDateTime`. Métodos: `isFromLia(): boolean`, `hasReservation(): boolean`. | 5.6.1; 5.6.7.1 |

### Value Objects

| Elemento | Atributos y métodos que define el informe | Sección del informe |
|---|---|---|
| `MessageContent` | `text: String`. Métodos `isBlank(): boolean`, `length(): int`. No puede estar vacío ni exceder la longitud máxima definida para el chat. | 5.6.1; 5.6.7.1 |
| `UserIntent` | `type: IntentType`, `cravings: String`, `budgetRange: PriceRange`, `location: GeoCoordinates`, `partySize: Integer`, `requestedAt: LocalDateTime`. Métodos `isComplete(): boolean`, `missingContext(): List<String>`. | 5.6.1; 5.6.7.1 |
| `PriceRange` | Aparece como tipo de `budgetRange`. El informe no define sus atributos. | 5.6.7.1 |
| `GeoCoordinates` | Aparece como tipo de `location`. El informe no define sus atributos en Messaging. | 5.6.7.1 |
| `ConversationId`, `MessageId` | Identificadores propios del contexto. | 5.6.1 |
| `ExplorerId`, `HuariqueId`, `ReservationId` | Identificadores emitidos por IAM, Business y Reservation. Messaging solo los referencia. | 5.6.1 |

### Enumeraciones

| Enum | Valores | Sección del informe |
|---|---|---|
| `SenderType` | `EXPLORER`, `LIA` | 5.6.1; 5.6.7.1 |
| `ConversationStatus` | `ACTIVE`, `CLOSED` | 5.6.1; 5.6.7.1 |
| `IntentType` | `RECOMMENDATION_REQUEST`, `HUARIQUE_INQUIRY`, `RESERVATION_REQUEST`, `UNKNOWN` | 5.6.1; 5.6.7.1 |
| `RoutingDecision` | `RECOMMEND_VIA_RADAR`, `QUERY_BUSINESS`, `FORWARD_TO_RESERVATION`, `ASK_CLARIFICATION` | 5.6.1; 5.6.7.1 |

### Repository, puerto y Domain Service

| Elemento | Tipo | Operaciones que define el informe | Sección del informe |
|---|---|---|---|
| `IConversationRepository` | Repository Interface | `save(c)`, `findById(id): Optional<Conversation>`, `findActiveByExplorerId(id): Optional<Conversation>`, `findAllByExplorerId(id): List<Conversation>`. | 5.6.1; 5.6.7.1 |
| `ILiaAgentGateway` | Port (ACL) | `requestInterpretation(id, content, ctx): void`. El dominio no conoce a n8n ni a OpenAI. | 5.6.1; 5.6.7.1 |
| `IntentRoutingService` | Domain Service | `route(intent): RoutingDecision`. `RECOMMEND_VIA_RADAR` (recomendación), `QUERY_BUSINESS` (menú, precios u horario), `FORWARD_TO_RESERVATION` (reserva completa), `ASK_CLARIFICATION` (falta contexto). | 5.6.1; 5.6.7.1 |

### Business Rules

| Regla | Sección del informe |
|---|---|
| Primero se interpreta el lenguaje natural del mensaje antes de decidir cualquier acción. | 5.6.1 |
| Las recomendaciones usan la ubicación y las preferencias del explorador. | 5.6.1 |
| Las solicitudes de reserva completas se reenvían automáticamente a Reservation; Messaging no valida horarios ni capacidad. | 5.6.1 |
| Si falta contexto (fecha, hora o cantidad de personas), Lía pide la aclaración. | 5.6.1 |
| Las respuestas de Lía son claras y conversacionales; un mensaje no puede estar vacío. | 5.6.1 |
| Solo una conversación activa acepta mensajes; los mensajes se registran a través del agregado y el historial queda ordenado cronológicamente. | 5.6.1 |

---

## B.2 Application Layer

| Elemento | Tipo | Qué define el informe | Sección del informe |
|---|---|---|---|
| `SendMessageCommand` | Command | `explorerId`, `conversationId` (opcional), `content` y ubicación del explorador (opcional). | 5.6.3 |
| `RegisterLiaReplyCommand` | Command | `conversationId`, contenido de la respuesta, intención interpretada, huariques sugeridos y reserva solicitada (opcional). | 5.6.3 |
| `SendMessageCommandHandler` | Command Handler | Obtiene o crea la conversación activa, registra el mensaje del explorador en `Conversation`, persiste con `IConversationRepository` y solicita la interpretación con `ILiaAgentGateway`. | 5.6.3 |
| `RegisterLiaReplyCommandHandler` | Command Handler | Recibe la intención y usa `IntentRoutingService` para decidir: consulta a Radar, consulta a Business, reenvío de la reserva a Reservation o pedido de aclaración. Luego registra la respuesta de Lía en la conversación. | 5.6.3 |
| `GetConversationsByExplorerQueryHandler` | Query Handler | Devuelve las conversaciones del explorador autenticado. | 5.6.3 |
| `GetMessagesByConversationQueryHandler` | Query Handler | Devuelve los mensajes de una conversación, verificando que pertenezca al explorador que consulta. | 5.6.3 |
| `IBusinessClient` | Outbound Port | Obtiene detalles, menú, precios, horarios y estado de un huarique. | 5.6.3 |
| `IRadarClient` | Outbound Port | Obtiene los huariques cercanos a la ubicación del explorador (búsqueda por proximidad H3). | 5.6.3 |
| `IReservationClient` | Outbound Port | Solicita una reserva y consulta su estado o confirmación. | 5.6.3 |
| Event Handlers | — | No hay. Messaging no consume eventos de RabbitMQ en esta versión. | 5.6.3; 4.2.5 |

La capa no contiene lógica de persistencia ni conoce detalles de n8n (5.6.3).

---

## B.3 Interface Layer

### Controllers y endpoints

| Elemento | Tipo | Qué define el informe | Sección del informe |
|---|---|---|---|
| `LiaChatController` | Controller | Recibe los mensajes del explorador a Lía. | 5.6.2 |
| `POST /messaging/chat` | Endpoint | Registra el mensaje (inicia una conversación si no hay una activa) y solicita su interpretación. Responde **202 Accepted** porque la respuesta de Lía llega de forma asíncrona. | 5.6.2 |
| `ConversationController` | Controller | Consulta del historial. | 5.6.2 |
| `GET /messaging/conversations/me` | Endpoint | Lista las conversaciones del explorador autenticado. | 5.6.2 |
| `GET /messaging/conversations/{conversationId}/messages` | Endpoint | Devuelve los mensajes de una conversación, incluidas las respuestas de Lía. | 5.6.2 |
| `N8nCallbackController` | Controller | Recibe la respuesta generada por el flujo de n8n. | 5.6.2 |
| `POST /messaging/callbacks/n8n` | Endpoint | Registra la respuesta de Lía. La respuesta llega primero al Integration System, que la reenvía aquí. No usa el JWT de un usuario; se protege con un secreto compartido entre el Integration System y Messaging. | 5.6.2 |

Todas las rutas se publican por Kong; los endpoints de usuario exigen el JWT de IAM (5.6.2).

### Resources

| Resource | Contenido que define el informe | Sección del informe |
|---|---|---|
| `SendMessageResource` | Texto del mensaje, identificador de la conversación (opcional) y, si el explorador comparte ubicación, latitud y longitud. | 5.6.2 |
| `MessageResource` | Identificador, emisor, contenido, intención detectada, referencia de reserva (si existe) y fecha de envío. | 5.6.2 |
| `ConversationResource` | Identificador, estado, fecha de inicio y fecha del último mensaje. | 5.6.2 |
| `LiaCallbackResource` | Conversación, texto de respuesta de Lía, intención interpretada, huariques sugeridos y, si corresponde, reserva solicitada. | 5.6.2 |

### Validaciones y respuestas

| Código | Cuándo | Sección del informe |
|---|---|---|
| 202 | Se acepta un mensaje. | 5.6.2 |
| 200 | Consultas. | 5.6.2 |
| 400 | Datos inválidos (mensaje vacío o coordenadas inválidas). | 5.6.2 |
| 401 | Sin token válido. | 5.6.2 |
| 403 | El rol no es Explorador Gastronómico. | 5.6.2 |
| 404 | La conversación no existe o no pertenece al usuario autenticado. | 5.6.2 |

---

## B.4 Infrastructure Layer

### Componentes

| Elemento | Tipo | Qué define el informe | Sección del informe |
|---|---|---|---|
| `ConversationRepository` | Repository | Implementa `IConversationRepository` con Spring Data JPA. Mapea `Conversation` y sus mensajes a las tablas `conversations` y `messages`. | 5.6.4 |
| MySQL (Aiven) | Persistence | Base propia del servicio. No comparte tablas. Los ids de explorador, huarique y reserva son referencias sin llave foránea física. | 5.6.4; 4.3.4 |
| `N8nLiaAgentGateway` | ACL | Implementa `ILiaAgentGateway`. Traduce `MessageContent` y el contexto de la conversación al formato del webhook de n8n, que invoca **a través del Integration System** (DD-07), y convierte la respuesta recibida en `LiaCallbackResource`. Si n8n no responde en el tiempo configurado, registra el error y el frontend ofrece la reserva manual (el informe cita RF-18). | 5.6.4; 4.2.5 |
| `BusinessClient` | REST Client | Implementa `IBusinessClient` con un cliente HTTP y propaga el JWT del explorador. | 5.6.4 |
| `RadarClient` | REST Client | Implementa `IRadarClient`; propaga el JWT del explorador. | 5.6.4 |
| `ReservationClient` | REST Client | Implementa `IReservationClient`; propaga el JWT del explorador. | 5.6.4 |
| `JwtAuthenticationFilter` | Security | Valida el token de IAM y extrae el identificador y el rol del usuario. | 5.6.4 |
| Protección del callback | Security | Se protege por separado con un secreto compartido configurado por variables de entorno. | 5.6.4; 5.6.2 |
| JWT de IAM | Seguridad | HMAC con clave compartida, claims con id y rol, expiración 60 min (RNF-03). | 5.1.4 |

### Tablas MySQL

| Tabla | Columnas | Sección del informe |
|---|---|---|
| `conversations` | `id CHAR(36)` PK NOT NULL; `explorer_id CHAR(36)` NOT NULL (ref. IAM); `status VARCHAR(10)` NOT NULL (ACTIVE / CLOSED); `started_at DATETIME` NOT NULL; `last_message_at DATETIME` NOT NULL | 5.6.7.2 |
| `messages` | `id CHAR(36)` PK; `conversation_id CHAR(36)` FK NOT NULL; `sender VARCHAR(10)` NOT NULL (EXPLORER / LIA); `content TEXT` NOT NULL; `intent_type VARCHAR(30)` NULL; `intent_data JSON` NULL (antojo, presupuesto, ubicación…); `huarique_id CHAR(36)` NULL (ref. Business); `reservation_id CHAR(36)` NULL (ref. Reservation); `sent_at DATETIME` NOT NULL | 5.6.7.2 |
| Relación | 1:N de `conversations` a `messages`. `explorer_id`, `huarique_id` y `reservation_id` no tienen FK física. | 5.6.7.2 |

### Endpoints de otros contextos que el informe define y Messaging puede usar

| Endpoint | Contexto | Observación | Sección del informe |
|---|---|---|---|
| `POST /radar/search` | Radar | Lo llama Messaging por REST con el JWT del explorador. | 5.2.2; 4.2.5 |
| `POST /reservation-api/reservations` | Reservation | Crea la reserva. Responde 201. Recibe huarique, fecha, hora y número de comensales. | 5.5.2 |
| `POST /business-api/huariques`, `PUT /business-api/huariques/{id}/menu` | Business | Son de escritura. Para consulta el informe solo dice que existen «operaciones de consulta», sin rutas. | 5.3.2 |
| Consulta del estado de una reserva | Reservation | 5.5.4 dice que Messaging la consulta por REST, pero 5.5.2 no lista un endpoint para ello. | 5.5.4; 5.5.2 |

### Eventos

| Evento | Qué define el informe | Sección del informe |
|---|---|---|
| Ninguno consumido | Messaging no consume eventos de RabbitMQ. | 4.2.5; 5.6.3; 4.3.4 |
| Ninguno publicado | El informe no define eventos publicados por Messaging. | 4.2.5 |

### Comunicación (DD-03) y ACL (DD-07)

| Regla | Sección del informe |
|---|---|
| REST síncrono: Messaging → Business (huarique, menú, precios, horarios), Radar (proximidad) y Reservation (solicitud y consulta), propagando el JWT del explorador. | 4.2.5 |
| n8n participa en la orquestación; consume OpenAI para interpretar y generar respuestas. Messaging lo mantiene desacoplado con una capa de integración. | 4.2.5; 5.6.4 |
| Contenedor: Messaging Service (MS), Spring Boot 3.x sobre Java 17, MySQL en Aiven. Delega en el Integration System la llamada a n8n. | 4.3.4; 5.6 |
