# Verificación de radar-service contra el informe

Revisión del código contra `docs/informe/informe-radar-messaging.md` (secciones 4.1.4, 4.2.5, 4.3.4, 5.1.4, 5.2 y 5.3.4)
y `docs/SPEC_RADAR_MESSAGING.md`. Fecha: 2026-11-13.

**Estados:** **OK** = igual al informe. **Distinto** = existe, pero con una diferencia que está registrada en
[`DEVIATIONS.md`](../DEVIATIONS.md) (la sección D tiene el texto exacto para corregir el informe). **Falta** = no está
implementado.

**`mvn verify`:** `BUILD SUCCESS`. 218 pruebas, 0 fallos, 0 omitidas, en 22 clases (incluye las de integración con
Testcontainers: Redis y RabbitMQ reales). Se construye el jar ejecutable (`radar-service-0.0.1-SNAPSHOT.jar`, 47,9 MB).

Las rutas son relativas a `src/main/java/pe/foodly/radar/`.

## Resumen

| Estado   | Cantidad |
|----------|----------|
| OK       | 60       |
| Distinto | 15       |
| Falta    | 2        |

## Domain Layer (5.2.1 y 5.2.7.1)

| Elemento del informe | Archivo | Estado | Nota | Prueba |
|---|---|---|---|---|
| `HuariqueLocation` (Aggregate Root): `huariqueId`, `coordinates`, `h3Cell`, `status` | `domain/model/aggregates/HuariqueLocation.java` | OK | Se agregan getters; la identidad es el `huariqueId` | `HuariqueLocationTest` |
| `HuariqueLocation.updateLocation(coordinates, h3Cell)` | ídem | OK | | `HuariqueLocationTest` |
| `HuariqueLocation.changeStatus(status)` | ídem | OK | | `HuariqueLocationTest` |
| `HuariqueLocation.isActive()` | ídem | OK | | `HuariqueLocationTest` |
| `HuariqueId(value)` | `domain/model/valueobjects/HuariqueId.java` | OK | Rechaza texto vacío | `ValueObjectsTest` |
| `GeoCoordinates(latitude, longitude)` y su validación (-90..90, -180..180) | `domain/model/valueobjects/GeoCoordinates.java` | OK | | `GeoCoordinatesTest` |
| `GeoCoordinates.distanceTo(other): Distance` | ídem | OK | Haversine, radio 6 371 008,8 m (Miraflores–Barranco: 3 252,96 m) | `GeoCoordinatesTest` |
| `H3Cell(index)` | `domain/model/valueobjects/H3Cell.java` | OK | Rechaza texto vacío | `ValueObjectsTest` |
| `CoverageZone(cells)` y `contains(cell)` | `domain/model/valueobjects/CoverageZone.java` | OK | Inmutable | `ValueObjectsTest` |
| `Distance(meters)` | `domain/model/valueobjects/Distance.java` | OK | Exige un valor finito `>= 0` | `ValueObjectsTest` |
| `AvailabilityStatus` (ACTIVE, INACTIVE) | `domain/model/valueobjects/AvailabilityStatus.java` | OK | | `HuariqueLocationTest` |
| `H3CellCalculated` | `domain/model/events/H3CellCalculated.java` | Distinto | Record con `coordinates`, `cell` y `occurredAt`; implementa `DomainEvent` | `RadarSearchServiceTest` |
| `NearbyHuariquesFound` | `domain/model/events/NearbyHuariquesFound.java` | Distinto | Record con `coordinates`, `kRing`, `total` y `occurredAt` | `RadarSearchServiceTest` |
| `NoHuariquesFound` | `domain/model/events/NoHuariquesFound.java` | Distinto | Record con `coordinates`, `kRing` y `occurredAt` | `RadarSearchServiceTest` |
| `IHuariqueLocationRepository.save(location)` | `domain/repositories/IHuariqueLocationRepository.java` | OK | Upsert idempotente | `RedisHuariqueLocationRepositoryIntegrationTest` |
| `IHuariqueLocationRepository.remove(id)` | ídem | OK | | ídem |
| `IHuariqueLocationRepository.findById(id)` | ídem | Distinto | Devuelve `Optional<HuariqueLocation>` | ídem |
| `IHuariqueLocationRepository.findByCells(cells)` | ídem | Distinto | Recibe `Set<H3Cell>` | ídem |
| `IH3IndexService.toCell(coordinates)` | `domain/services/IH3IndexService.java` | OK | | `UberH3IndexServiceTest` |
| `IH3IndexService.kRing(cell, k)` | ídem | OK | | `UberH3IndexServiceTest` |
| `RadarSearchService.search(coordinates, kRing): List<HuariqueLocation>` | `domain/services/RadarSearchService.java` | OK | Firma idéntica al diagrama | `RadarSearchServiceTest` |
| `RadarSearchService` «publica» los domain events | ídem | Distinto | Los **registra**; la capa de aplicación los recoge con `pullDomainEvents()` y los publica | `RadarSearchServiceTest`, `SearchNearbyHuariquesQueryHandlerTest` |
| Regla: coordenadas válidas | `GeoCoordinates.java` | OK | | `GeoCoordinatesTest` |
| Regla: solo activos dentro de la zona | `RadarSearchService.java` | OK | | `RadarSearchServiceTest` |
| Regla: orden por distancia ascendente | ídem | OK | Desempata por `huariqueId` | `RadarSearchServiceTest` |
| Regla: zona vacía devuelve lista vacía | ídem | OK | Registra `NoHuariquesFound` | `RadarSearchServiceTest` |
| Regla: el upsert es idempotente | `infrastructure/persistence/redis/RedisHuariqueLocationRepository.java` | OK | | `RedisHuariqueLocationRepositoryIntegrationTest` |

## Application Layer (5.2.3)

| Elemento del informe | Archivo | Estado | Nota | Prueba |
|---|---|---|---|---|
| `UpsertHuariqueLocationCommand` | `application/commands/UpsertHuariqueLocationCommand.java` | OK | | `UpsertHuariqueLocationCommandHandlerTest` |
| `ChangeHuariqueAvailabilityCommand` | `application/commands/ChangeHuariqueAvailabilityCommand.java` | OK | | `ChangeAndRemoveCommandHandlersTest` |
| `RemoveHuariqueLocationCommand` | `application/commands/RemoveHuariqueLocationCommand.java` | OK | | `ChangeAndRemoveCommandHandlersTest` |
| `UpsertHuariqueLocationCommandHandler` | `application/commandhandlers/UpsertHuariqueLocationCommandHandler.java` | OK | Recalcula la celda y guarda | `UpsertHuariqueLocationCommandHandlerTest` |
| `ChangeHuariqueAvailabilityCommandHandler` | `application/commandhandlers/ChangeHuariqueAvailabilityCommandHandler.java` | Distinto | Falla con `HuariqueLocationNotFoundException` si el huarique no está indexado | `ChangeAndRemoveCommandHandlersTest` |
| `RemoveHuariqueLocationCommandHandler` | `application/commandhandlers/RemoveHuariqueLocationCommandHandler.java` | OK | | `ChangeAndRemoveCommandHandlersTest` |
| Evento que dispara `RemoveHuariqueLocationCommand` | — | **Falta** | El informe no define ningún evento que lo dispare | — |
| `SearchNearbyHuariquesQuery` | `application/queries/SearchNearbyHuariquesQuery.java` | OK | | `SearchNearbyHuariquesQueryHandlerTest` |
| `SearchNearbyHuariquesQueryHandler` | `application/queryhandlers/SearchNearbyHuariquesQueryHandler.java` | Distinto | Devuelve `List<NearbyHuarique>` (huarique y distancia); valida el k-ring máximo; publica los domain events | `SearchNearbyHuariquesQueryHandlerTest` |
| `GetRadarStatusQueryHandler` | `application/queryhandlers/GetRadarStatusQueryHandler.java` | Distinto | «Estado operativo» = Redis responde | `GetRadarStatusQueryHandlerTest` |
| `HuariqueRegisteredEventHandler` | `application/eventhandlers/HuariqueRegisteredEventHandler.java` | OK | Ejecuta `UpsertHuariqueLocationCommand` | `BusinessEventHandlersTest` |
| `HuariqueUpdatedEventHandler` | `application/eventhandlers/HuariqueUpdatedEventHandler.java` | OK | Ejecuta `UpsertHuariqueLocationCommand` | `BusinessEventHandlersTest` |
| `LocationUpdatedEventHandler` | `application/eventhandlers/LocationUpdatedEventHandler.java` | OK | Ejecuta `UpsertHuariqueLocationCommand` con las nuevas coordenadas | `BusinessEventHandlersTest` |
| `OpeningStatusChangedEventHandler` | `application/eventhandlers/OpeningStatusChangedEventHandler.java` | OK | Ejecuta `ChangeHuariqueAvailabilityCommand` | `BusinessEventHandlersTest` |

## Interface Layer (5.2.2)

| Elemento del informe | Archivo | Estado | Nota | Prueba |
|---|---|---|---|---|
| `RadarController` | `interfaces/rest/RadarController.java` | OK | | `RadarControllerTest` |
| `POST /radar/search` | ídem | OK | 200 con la lista ordenada | `RadarControllerTest`, `RadarServiceApplicationIntegrationTest` |
| `RadarStatusController` | `interfaces/rest/RadarStatusController.java` | OK | | `RadarStatusControllerTest` |
| `GET /radar/status` | ídem | Distinto | 200 `UP`; **503** `DOWN` si Redis no responde | `RadarStatusControllerTest` |
| `RadarSearchResource` | `interfaces/rest/resources/RadarSearchResource.java` | OK | `latitude`, `longitude`, `kRing` | `RadarControllerTest` |
| `RadarSearchResultResource` | `interfaces/rest/resources/RadarSearchResultResource.java` | OK | `huariques` y `total` | `RadarControllerTest` |
| `HuariqueLocationResource` | `interfaces/rest/resources/HuariqueLocationResource.java` | OK | `huariqueId`, `latitude`, `longitude`, `distanceMeters` | `RadarControllerTest` |
| `RadarStatusResource` | `interfaces/rest/resources/RadarStatusResource.java` | OK | | `RadarStatusControllerTest` |
| Validación: coordenadas o k-ring inválidos devuelven 400 con mensaje | `interfaces/rest/GlobalExceptionHandler.java` | OK | «No se pudo procesar la consulta del radar: <detalle>»; k-ring de 0 a 5 | `RadarControllerTest` |
| Zona sin huariques devuelve 200 con lista vacía | `RadarController.java` | OK | `{ "huariques": [], "total": 0 }` | `RadarControllerTest`, `RadarServiceApplicationIntegrationTest` |
| Sin token válido devuelve 401 | `infrastructure/security/SecurityConfig.java` | Distinto | El informe lo atribuye al API Gateway; Radar también lo valida (DD-06, QA-1) y agrega **403** para otro rol | `RadarControllerTest`, `JwtAuthenticationFilterTest` |

## Infrastructure Layer (5.2.4, 5.2.7.2, 5.1.4)

| Elemento del informe | Archivo | Estado | Nota | Prueba |
|---|---|---|---|---|
| `RedisHuariqueLocationRepository` (Spring Data Redis, Lettuce) | `infrastructure/persistence/redis/RedisHuariqueLocationRepository.java` | OK | Guardado y borrado atómicos (script Lua); búsqueda con pipelining | `RedisHuariqueLocationRepositoryIntegrationTest` |
| Hash `radar:huarique:{huariqueId}`: `lat`, `lng`, `h3Cell`, `status`, `updatedAt` | ídem | OK | | ídem |
| Campo `version` del hash | ídem | Distinto | El diagrama 5.2.7.2 no lo muestra, pero 5.2.4 lo usa | ídem |
| Set `radar:cell:{h3Index}` | ídem | OK | Al mover de celda, el id sale del set anterior | ídem |
| `UberH3IndexService` | `infrastructure/h3/UberH3IndexService.java` | OK | Resolución fija 9 | `UberH3IndexServiceTest` |
| `BusinessEventListener` (`@RabbitListener`) | `infrastructure/messaging/BusinessEventListener.java` | OK | Ack manual, un evento a la vez | `BusinessEventListenerTest`, `BusinessEventsIntegrationTest` |
| `RabbitMQConfig`: binding a los cuatro eventos | `infrastructure/messaging/RabbitMQConfig.java` | OK | | `BusinessEventsIntegrationTest` |
| `RabbitMQConfig`: Dead Letter Queue | ídem | OK | `foodly.dlx` y `radar.business-events.dlq` | `BusinessEventsIntegrationTest` |
| Evento `HuariqueRegistered` | `infrastructure/messaging/BusinessEventType.java` | OK | | `BusinessEventsIntegrationTest` |
| Evento `HuariqueUpdated` | ídem | OK | Debe traer coordenadas (a confirmar con Business) | ídem |
| Evento `LocationUpdated` | ídem | OK | Mueve el huarique de celda | ídem |
| Evento `OpeningStatusChanged` | ídem | OK | | ídem |
| `MenuUpdated` | ídem | OK | No se enlaza a la cola; si llega, se confirma y se ignora | `BusinessEventListenerTest`, `BusinessEventsIntegrationTest` |
| Comparar `version` y descartar si es menor o igual | `infrastructure/messaging/EventVersionRule.java` | OK | | `EventVersionRuleTest`, `BusinessEventsIntegrationTest` |
| Ack manual solo después de actualizar Redis | `BusinessEventListener.java` | OK | | `BusinessEventListenerTest` |
| Tras tres fallos, el mensaje pasa a la DLQ | ídem | OK | 3 intentos con espera creciente | `BusinessEventListenerTest`, `BusinessEventsIntegrationTest` |
| Si Radar está caído, los eventos esperan en la cola (QA-3) | `docker-compose.local.yml`, `scripts/pr05-consumer-down.sh` | OK | PR-05: 20 de 20 aplicados, 0 duplicados | `docs/evidencias/pr05-2026-11-13.txt` |
| `RedisConfig` toma `REDIS_URL` (Upstash, `rediss://`) | `infrastructure/config/RedisConfig.java` | OK | TLS probado solo a nivel de configuración, no contra un Upstash real | `RedisConfigTest` |
| `RabbitMQConfig` toma `RABBITMQ_URL` (CloudAMQP, `amqps://`) | `application.yml` (`spring.rabbitmq.addresses`) | Distinto | La conexión la arma Spring Boot; no se probó contra un CloudAMQP real | `RabbitUrlConfigurationTest` |
| Spring Boot Actuator como base de `/radar/status` | `GetRadarStatusQueryHandler.java` | OK | | `RadarServiceApplicationIntegrationTest` |
| JWT: cada servicio valida firma, expiración, id de usuario y rol (5.1.4, DD-06) | `infrastructure/security/JwtAuthenticationFilter.java` | OK | HMAC con `JWT_SECRET`; claims configurables | `JwtAuthenticationFilterTest` |
| Reconciliación Business–Radar (DD-08) | — | **Falta** | Propuesta del informe, fuera de este sprint | — |
| Radar como contenedor Spring Boot en Docker Compose (4.3.4, DD-10) | `Dockerfile`, `docker-compose.local.yml` | Distinto | Se agregó un `HEALTHCHECK` que refleja Redis y RabbitMQ | — |
| H3 no calcula rutas ni tiempos de llegada (DD-05) | `GeoCoordinates.java` | OK | Solo distancia por coordenadas | `GeoCoordinatesTest` |

## Qué no se pudo verificar

- **TLS contra servicios reales.** No se probó una conexión `rediss://` a Upstash ni `amqps://` a CloudAMQP; solo se
  comprobó cómo se interpretan las URLs.
- **Integración con Business, IAM y Kong.** Los eventos y el JWT se probaron con mensajes y tokens generados en las
  pruebas (`scripts/seed-events.sh`, `scripts/dev-token.sh`). Falta confirmar con Business el formato del evento y con
  IAM cómo deriva la clave de firma del secreto.
- **QA-2 (95 % de las respuestas en ≤ 2 s).** No se midió con carga; solo se comprobó el funcionamiento.
