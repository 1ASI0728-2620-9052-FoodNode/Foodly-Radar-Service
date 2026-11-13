# Extracto del informe TP (1ASI0728) — secciones que usa la implementación de Radar y Messaging

> Generado desde TP_1ASI0728_202620_TP_corregido.docx (13/11/2026). Solo lectura: es la fuente de verdad del agente.

### 4.1.4. Architectural Design Decisions

Las decisiones se fundamentan en los drivers de 4.1.3 y comparan alternativas, beneficios y limitaciones. Una tecnología no es obligatoria solo por estar implementada.

**Evaluación de alternativas: monolito modular y microservicios**

| **Criterio**                  | **Monolito modular**                                                      | **Microservicios**                                                                                                                                          |
|-------------------------------|---------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Complejidad                   | Menor al inicio.                                                          | Mayor por la distribución de servicios.                                                                                                                     |
| Costos                        | Menores costos iniciales.                                                 | Mayores costos operativos.                                                                                                                                  |
| Autonomía                     | Un solo despliegue.                                                       | Despliegue por servicio.                                                                                                                                    |
| Escalabilidad                 | Escalado de toda la aplicación.                                           | Escalado selectivo por servicio.                                                                                                                            |
| Mantenimiento                 | Más simple al inicio.                                                     | Exige coordinación y observabilidad.                                                                                                                        |
| Fallos                        | Un fallo puede afectar al conjunto.                                       | Aísla fallos, salvo dependencias compartidas.                                                                                                               |
| Ajuste al contexto de Foodly. | Viable para la carga prevista (1 000 huariques y 10 000 usuarios, RNF-06. | Justificado por el escalado independiente de Radar, el aislamiento de fallos de Messaging frente a n8n y OpenAI, y la persistencia específica por contexto. |

Foodly mantiene seis Bounded Contexts (IAM, Business, Radar, Community, Reservation y Messaging). Un Bounded Context no corresponde obligatoriamente a un microservicio; aquí se decide desplegarlos por separado por tres razones: el Radar es una carga de lectura intensiva que conviene escalar de forma independiente; Messaging depende de servicios externos (n8n y OpenAI) y su fallo no debe afectar a Business ni a Reservation; y cada contexto usa el motor de persistencia que mejor se ajusta a sus datos. Se reconoce que para la escala prevista un monolito modular sería viable y menos costoso, y que los microservicios aumentan la complejidad operativa. Por ello la decisión se validará escalando el Radar de forma independiente bajo la carga de RNF-01 y evaluando el costo operativo adicional. Estos servicios atienden por igual a la aplicación web (Vue.js) y a la aplicación móvil (React Native), que consumen los mismos endpoints a través de Kong.

**Decisiones arquitectónicas**

| **ID** | **Decisión**                                                                              | **Alternativa**                      | **Drivers**            | **Consecuencia**                                                                                                                        |
|--------|-------------------------------------------------------------------------------------------|--------------------------------------|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| DD-01  | Seis microservicios, uno por Bounded Context.                                             | Monolito modular.                    | AD05, AD08, AD09, AD10 | Permite despliegues autónomos; aumenta complejidad y costos.                                                                            |
| DD-02  | PostgreSQL: IAM/Reservation; MongoDB: Business/Community; MySQL: Messaging; Redis: Radar. | Motor único compartido.              | AD06, AD09             | Adapta la persistencia; exige administrar varias tecnologías y consistencia.                                                            |
| DD-03  | REST para consultas inmediatas y RabbitMQ para eventos de negocio.                        | Solo REST o solo mensajería.         | AD04, AD05, AD18       | Diferencia consultas y eventos; requiere dos mecanismos de comunicación.                                                                |
| DD-04  | STOMP/WebSocket para comunicar cambios a los clientes.                                    | Polling periódico.                   | AD04                   | Reduce consultas repetitivas; la latencia de ≤ 5 s debe verificarse.                                                                    |
| DD-05  | H3 selecciona celdas; Redis agiliza consultas; distancia según coordenadas.               | Índices geoespaciales tradicionales. | AD01, AD10             | Facilita agrupación espacial y reduce candidatos; no calcula rutas ni garantiza mayor precisión; la precisión depende de la resolución. |
| DD-06  | Kong como gateway, con validación adicional de permisos en los servicios.                 | Validación en una sola capa.         | AD07, AD08             | Centraliza el acceso; el gateway puede ser punto único de fallo.                                                                        |
| DD-07  | Integration System para n8n y Cloudinary.                                                 | Integraciones directas.              | AD02, AD18             | Reduce acoplamiento externo; añade un componente.                                                                                       |
| DD-08  | Propuesta: Outbox, idempotencia, confirmaciones y reconciliación Business–Radar.          | Publicación directa sin control.     | AD04, AD05, AD06       | Mitiga pérdidas y duplicados; requiere implementación y pruebas.                                                                        |
| DD-09  | Community replica eventos de reservas aprobadas de Reservation.                           | Consulta síncrona en cada reseña.    | AD03, AD18             | Reduce dependencia directa; introduce consistencia eventual.                                                                            |
| DD-10  | Docker Compose para el prototipo, con salud, reinicio y plan de recuperación.             | Kubernetes inicial.                  | AD08, AD09             | Menor costo inicial; un solo host no garantiza alta disponibilidad.                                                                     |

**Diseño de publicación confiable de eventos**

**Publicación (Transactional Outbox):** Cuando Business actualiza el catálogo, guarda el cambio y el evento en la misma transacción local (colección outbox de MongoDB, mediante una transacción multidocumento). Un proceso en segundo plano (Outbox Publisher) lee los eventos no enviados y los publica en RabbitMQ con confirmación del broker (publisher confirms), marcándolos como enviados tras confirmación.

**Consumo (At-Least-Once e Idempotencia):** Radar consume desde una cola durable con ack manual tras actualizar Redis. Para evitar efectos duplicados por retransmisiones, registra el identificador de evento procesado o realiza la actualización de Redis de forma idempotente (SADD / HSET con la versión del evento).

**Reconciliación (Consistencia Final):** Un proceso periódico de reconciliación compara el catálogo activo en Business con las celdas H3 en Redis y re-publica los eventos faltantes o reconstruye las celdas desincronizadas, garantizando que el índice refleje el estado real aunque ocurran fallos prolongados.
### 4.2.5. Context Mapping

El proceso de Context Mapping permitió representar las relaciones estructurales y los contratos de integración entre los bounded contexts definidos en Foodly. Mientras que los Bounded Context Canvases detallan cada contexto de manera aislada, el Context Map ofrece una visión sistémica de cómo IAM, Business, Radar, Community, Messaging y Reservation colaboran e intercambian información dentro de los límites de la solución.

- Para su elaboración se aplicaron preguntas de exploración sugeridas en Domain-Driven Design, como:

- ¿Qué sucede si un contexto debe actuar como proveedor (supplier) de otro que depende de sus datos?

- ¿Qué interacciones requieren comunicación síncrona y cuáles pueden propagarse mediante eventos de dominio?

- ¿Qué patrones de integración permiten mantener independientes los modelos internos frente a servicios externos?

A partir de este análisis se identificaron y aplicaron distintos patrones de relación entre contextos:

- Customer/Supplier. Se aplica principalmente en las relaciones de Messaging con Business, Radar y Reservation. Business proporciona información del huarique, como menú, precios, horarios y estado del establecimiento; Radar proporciona resultados de búsqueda y descubrimiento por proximidad basados en H3; y Reservation administra las operaciones relacionadas con la solicitud, consulta de estado y confirmación de reservas. Messaging consume estas capacidades para responder a las necesidades expresadas por el usuario durante su interacción con Lía.

- Open Host Service (OHS). IAM proporciona un contrato común de autenticación y autorización mediante JWT para los bounded contexts protegidos del backend. De esta manera, la gestión de identidad, roles y permisos permanece centralizada, evitando que cada contexto implemente independientemente estas responsabilidades.

- Event-Driven Consistency. Se utiliza para propagar cambios relevantes entre Business, Radar y Community. Business publica de manera asíncrona eventos como HuariqueRegistered, HuariqueUpdated, LocationUpdated y OpeningStatusChanged, permitiendo que Radar mantenga actualizada la información necesaria para realizar búsquedas por proximidad mediante H3. Asimismo, Business y Community intercambian información relacionada con las actualizaciones del huarique y su reputación. Con la arquitectura planteada, esta coordinación asíncrona se realiza mediante RabbitMQ administrado en CloudAMQP.

- Anti-Corruption Layer (ACL). Se aplica en las integraciones que cruzan los límites de Foodly Platform con servicios externos. Business mantiene desacoplado su modelo interno de Cloudinary para las operaciones relacionadas con la carga y recuperación de imágenes. De manera equivalente, Messaging mantiene desacoplado su modelo interno de n8n mediante una capa de integración; n8n participa en la orquestación del flujo conversacional y consume OpenAI para procesar las solicitudes y generar respuestas utilizadas por Lía.

- Tipo de comunicación entre contextos (DD-03). (a) REST síncrono: Messaging → Business (huarique, menú, precios y horarios), Messaging → Radar (búsqueda por proximidad) y Messaging → Reservation (solicitud y consulta de reservas), mediante llamadas directas que propagan el JWT del explorador; Messaging no consume eventos de RabbitMQ. (b) Eventos asíncronos en RabbitMQ (CloudAMQP): Business → Radar (HuariqueRegistered, HuariqueUpdated, LocationUpdated y OpeningStatusChanged), Business → Community (HuariqueUpdated) y Reservation → Community (ReservaAceptada, que habilita la reseña del explorador). (c) JWT: IAM expone el contrato de autenticación (Open Host Service) que los demás contextos validan (RNF-03).

De acuerdo con las relaciones y patrones identificados, se obtuvo el siguiente Context Map de Foodly:



El bounded context Messaging representa una capacidad propia del dominio conversacional de Foodly y no únicamente una integración tecnológica. Su responsabilidad comprende la gestión de las interacciones realizadas mediante Lía, las sesiones conversacionales y las solicitudes expresadas por los usuarios. Para satisfacer estas solicitudes, Messaging se coordina con Business, Radar y Reservation, consumiendo sus capacidades sin asumir las reglas de negocio que pertenecen a dichos contextos.

En este sentido, Business actúa como proveedor de la información relacionada con los huariques, menús, precios, horarios y estado del establecimiento; Radar proporciona capacidades de descubrimiento geoespacial y búsqueda por proximidad mediante H3; mientras que Reservation administra el ciclo de vida de las reservas, incluyendo su solicitud, estado y confirmación. Messaging utiliza dichas capacidades para construir la experiencia conversacional de Lía dentro de Foodly.

El Context Map resultante establece responsabilidades claramente diferenciadas entre los seis bounded contexts. IAM administra la identidad, autenticación y autorización de los usuarios; Business gestiona la información de los huariques, sus menús, horarios y estado; Radar soporta el descubrimiento geoespacial y las búsquedas por proximidad mediante H3; Community administra reseñas, calificaciones y favoritos; Reservation controla el ciclo de vida de las reservas; y Messaging administra la experiencia conversacional de Lía y coordina las solicitudes del usuario con los demás contextos involucrados.

Finalmente, las integraciones externas con Cloudinary, n8n y OpenAI API permanecen desacopladas de los modelos internos de Foodly mediante mecanismos de integración apropiados. De esta manera, los bounded contexts mantienen sus responsabilidades y modelos de dominio independientes, reduciendo el impacto que podrían generar cambios en los contratos o tecnologías utilizadas por servicios externos.

## 4.3. Software Architecture

### 4.3.4. Software Architecture Deployment Diagram

### 

\[Link: [<u>https://online.visual-paradigm.com/share.jsp?id=323733313631302d32</u>](https://online.visual-paradigm.com/share.jsp?id=323733313631302d32)\]

El diagrama de despliegue muestra cómo los seis Bounded Contexts definidos en la sección 4.2 se materializan en unidades desplegables del prototipo. Un Bounded Context no corresponde necesariamente a un microservicio; en Foodly se optó por desplegar cada uno en su propio contenedor (DD-01), con una base de datos propia por contexto. La siguiente tabla explicita esa correspondencia e identifica los componentes que no son Bounded Contexts:

| Bounded Context            | Unidad desplegable (contenedor)                    | Persistencia                      | Comunicación                                                                                                     |
|----------------------------|----------------------------------------------------|-----------------------------------|------------------------------------------------------------------------------------------------------------------|
| IAM                        | Identity & Access Service (IAMS), Spring Boot 3.x  | PostgreSQL en Supabase            | REST vía Kong; emite el JWT que validan los demás servicios (Open Host Service).                                 |
| Business                   | Business Management Service (BMS), Spring Boot 3.x | MongoDB Atlas M0                  | REST vía Kong; publica eventos de huarique en RabbitMQ mediante Outbox (DD-08); imágenes vía Integration System. |
| Radar                      | Geo-Radar Engine Service (GRES), Spring Boot 3.x   | Redis en Upstash                  | REST vía Kong y desde Messaging; consume eventos de Business desde una cola durable.                             |
| Community                  | Community Service (CS), Spring Boot 3.x            | MongoDB Atlas M0 (clúster propio) | REST vía Kong; consume HuariqueUpdated de Business y ReservaAceptada de Reservation.                             |
| Reservation                | Reservation Service (RS), Spring Boot 3.x          | PostgreSQL en Neon                | REST vía Kong y desde Messaging; publica ReservaAceptada.                                                        |
| Messaging                  | Messaging Service (MS), Spring Boot 3.x            | MySQL en Aiven                    | REST hacia Business, Radar y Reservation; delega en el Integration System la llamada a n8n. No consume eventos.  |
| No es Bounded Context      | Kong API Gateway                                   | —                                 | Punto de entrada único de las aplicaciones web y móvil; valida el JWT en el borde.                               |
| No es Bounded Context      | Integration System, Spring Boot 3.x                | —                                 | Fachada técnica (ACL, DD-07) hacia n8n y Cloudinary; no contiene reglas de negocio.                              |
| Infraestructura compartida | RabbitMQ en CloudAMQP                              | Colas y mensajes durables         | Transporte de eventos de dominio entre contextos.                                                                |

Los contenedores propios se ejecutan con Docker Compose en un único host (DD-10), mientras que las bases de datos, el broker y la caché se consumen como servicios administrados en capa gratuita. Como los límites de cada Bounded Context no dependen del contenedor en que se ejecutan, si en el futuro se decidiera agrupar dos contextos en un mismo despliegue (por ejemplo, Community y Reservation para reducir costos), sus modelos de dominio y contratos se mantendrían sin cambios; solo cambiaría la unidad desplegable.

# Capítulo V: Tactical-Level Software Design

## 5.1. Bounded Context: Identity

### 5.1.1. Domain Layer

La Domain Layer del Bounded Context IAM contiene el núcleo de la gestión de identidad de Foodly. Su responsabilidad es administrar el ciclo de vida de la cuenta de un usuario, registro, autenticación y consulta de perfil, y emitir el token de sesión que el resto de los Bounded Contexts utiliza para validar, de forma independiente, la identidad y el rol de quien ejecuta una operación protegida. Este contexto no conoce reglas de negocio de huariques, reservas ni reseñas: solo responde “quién es este usuario y qué puede hacer”.

**Aggregate Roots**

- User representa la cuenta de un usuario registrado en Foodly. Contiene su identificador, nombre, email, username, teléfono (opcional), el hash de su contraseña, su rol (Explorador Gastronómico o Dueño de Huarique) y su estado de cuenta (ACTIVE / DEACTIVATED). Es la única entidad a través de la cual se modifican las credenciales, el rol o el estado de la cuenta.

**Value Objects**

- Email: Valida formato y se normaliza en minusculas; es el identificador único de inicio de sesión.

- Username: Nombre de usuario único, validado por longitud y caracteres permitidos.

- PasswordHash: representa la contraseña ya cifrada; el dominio nunca maneja la contraseña en texto plano.

- PhoneNumber: Teléfono de contacto opcional del usuario.

- Role: Enumeración con los valores GASTRONOMIC_EXPLORER y HUARIQUE_OWNER.

### 5.1.4. Infrastructure Layer

La Infrastructure Layer implementa los contratos del dominio y de la aplicación, y administra la persistencia, el cifrado de contraseñas y la emisión/validación del token de sesión que el resto de los Bounded Contexts consume como Open Host Service (Sección 4.2.5).

**Repositories**

- UserRepository implementa IUserRepository con Spring Data JPA sobre PostgreSQL, de acuerdo con la persistencia políglota de la Sección 4.1.1 (IAM usa base de datos relacional para garantizar consistencia transaccional en credenciales y roles).

**Security**

- **BCryptPasswordHasher** implementa IPasswordHasher con BCrypt y factor de costo 12 (RNF-03).

- **JwtTokenIssuer** implementa ITokenIssuer: genera el JWT con el identificador del usuario y su rol como claims, firmado con una clave compartida entre los servicios, y expiración de 60 minutos (RNF-03). También valida la firma y la expiración de un token cuando se le solicita.

- **JwtAuthenticationFilter** protege los endpoints que exigen sesión (como GET /iam/users/me) validando el token de la misma forma que lo hacen Business, Radar, Community, Reservation y Messaging sobre los tokens emitidos por IAM.

**Persistence**

La información de cuentas se almacena en una base de datos PostgreSQL propia del contexto, independiente de las de los demás servicios. Ningún otro Bounded Context accede directamente a esta base: todos confían exclusivamente en el JWT emitido por IAM.

Configuración

- **SecurityConfig** declara las rutas públicas (/iam/auth/register, /iam/auth/login) y las protegidas del servicio.

- La clave de firma del JWT y la configuración de expiración se toman de variables de entorno, compartidas de forma segura con los demás microservicios para que puedan validar el token de forma independiente (QA-1).

## 5.2 Bounded Context: Radar

### 5.2.1. Domain Layer

La Domain Layer del Bounded Context Radar contiene el núcleo de la lógica de descubrimiento geoespacial de Foodly. Su responsabilidad es mantener una representación mínima de la ubicación de cada huarique, calcular la zona de cobertura del explorador mediante indexación hexagonal H3 y determinar qué establecimientos activos están dentro de esa zona, ordenados por distancia. Este contexto no administra el perfil, el menú ni las reseñas de los huariques, que pertenecen a Business y Community.

**Aggregate Roots**

- **HuariqueLocation** representa la ubicación indexada de un huarique dentro del radar. Contiene el identificador del huarique, sus coordenadas, la celda H3 que le corresponde y su estado de disponibilidad. Cuando cambian las coordenadas, el agregado recalcula su celda H3. Solo los huariques con estado activo pueden aparecer en los resultados.

**Value Objects**

- **HuariqueId**: identificador del huarique, emitido por Business.

- **GeoCoordinates**: latitud y longitud. Valida que la latitud esté entre -90 y 90 y la longitud entre -180 y 180.

- **H3Cell**: identificador de la celda hexagonal de una ubicación, calculada con una resolución fija.

- **CoverageZone**: conjunto de celdas H3 (celda central más k-ring) donde se busca.

- **Distance**: distancia en metros entre el explorador y un huarique.

- **AvailabilityStatus**: estado del huarique en el radar (ACTIVE / INACTIVE).

**Domain Events**

- **H3CellCalculated**: se emite cuando se calcula la celda H3 del explorador.

- **NearbyHuariquesFound**: se emite cuando la búsqueda devuelve al menos un huarique.

- **NoHuariquesFound**: se emite cuando la zona de cobertura no tiene huariques.

**Repository Interfaces**

- **IHuariqueLocationRepository** define las operaciones para guardar (upsert), eliminar, consultar por identificador y obtener los huariques que pertenecen a un conjunto de celdas H3, sin depender de Redis.

**Domain Services**

- **RadarSearchService** calcula la zona de cobertura del explorador, obtiene los candidatos de las celdas incluidas, calcula la distancia a cada uno y los ordena de menor a mayor.

- **IH3IndexService** (puerto del dominio) define las operaciones de indexación: obtener la celda de unas coordenadas y obtener el k-ring de una celda. Así el dominio no depende de la librería Uber H3, que se implementa en Infrastructure.

**Business Rules**

- Las coordenadas deben ser válidas.

- Solo se devuelven huariques activos dentro de la zona de cobertura.

- Los resultados se ordenan por distancia ascendente.

- Si no hay establecimientos cercanos, se devuelve una lista vacía.

- La actualización de una ubicación es idempotente: reemplaza el registro anterior del mismo huarique.

### 5.2.2. Interface Layer

La Interface Layer expone el servicio de búsqueda geoespacial a las aplicaciones web y móvil de Foodly. Recibe las solicitudes HTTP a través del API Gateway (Kong), valida los datos de entrada y delega el procesamiento a la Application Layer.

**Controllers**

- **RadarController** gestiona la búsqueda de huariques cercanos.

  - **POST /radar/search**: recibe latitud, longitud y número de anillos H3 (k-ring), y devuelve los huariques de la zona de cobertura ordenados por distancia.

- **RadarStatusController** permite verificar que el servicio está operativo.

  - **GET /radar/status**: responde 200 si el servicio está activo.

**Resources**

- **RadarSearchResource**: latitud, longitud y k-ring de la búsqueda.

- **RadarSearchResultResource**: lista de huariques encontrados y total de resultados.

- **HuariqueLocationResource**: identificador, coordenadas y distancia en metros de cada huarique.

- **RadarStatusResource**: estado del servicio.

**Validaciones y respuestas**

- Solicitud válida: HTTP 200 con la lista ordenada.

- Coordenadas fuera de rango o k-ring inválido: HTTP 400 con un mensaje que indica que no se pudo procesar la consulta.

- Zona sin huariques: HTTP 200 con lista vacía. El frontend muestra el mensaje «No hay huariques aquí aún» (US-15).

- Sin token válido: 401, rechazado por el API Gateway antes de llegar al servicio.

### 5.2.3. Application Layer

La Application Layer coordina los casos de uso del radar. Recibe las solicitudes de la Interface Layer y los eventos de Business, y los traduce en operaciones sobre el dominio, sin contener lógica de persistencia.

**Commands**

- **UpsertHuariqueLocationCommand**: huariqueId, latitud, longitud y estado. Se usa al registrar un huarique o cambiar su ubicación.

- **ChangeHuariqueAvailabilityCommand**: huariqueId y nuevo estado.

- **RemoveHuariqueLocationCommand**: huariqueId. Elimina el huarique del índice.

**Command Handlers**

- **UpsertHuariqueLocationCommandHandler** crea o actualiza el agregado HuariqueLocation, recalcula su celda H3 y lo guarda mediante IHuariqueLocationRepository. Si la celda cambió, el repositorio lo mueve del índice anterior al nuevo.

- **ChangeHuariqueAvailabilityCommandHandler** actualiza el estado del agregado.

- **RemoveHuariqueLocationCommandHandler** elimina la ubicación indexada.

**Queries y Query Handlers**

- **SearchNearbyHuariquesQuery**: latitud, longitud y k-ring.

- **SearchNearbyHuariquesQueryHandler** crea las coordenadas, calcula la celda H3 y la zona de cobertura, y consulta los candidatos mediante RadarSearchService. Devuelve los resultados ordenados por distancia.

- **GetRadarStatusQueryHandler** devuelve el estado operativo del servicio.

**Event Handlers (integración con Business)**

- **HuariqueRegisteredEventHandler**: ejecuta UpsertHuariqueLocationCommand.

- **HuariqueUpdatedEventHandler**: ejecuta UpsertHuariqueLocationCommand.

- **LocationUpdatedEventHandler**: ejecuta UpsertHuariqueLocationCommand con las nuevas coordenadas.

- **OpeningStatusChangedEventHandler**: ejecuta ChangeHuariqueAvailabilityCommand.

### 5.2.4. Infrastructure Layer

La Infrastructure Layer implementa los contratos del dominio y gestiona la persistencia en Redis, la indexación H3 y la mensajería asíncrona.

**Repositories**

- **RedisHuariqueLocationRepository** implementa IHuariqueLocationRepository con Spring Data Redis (Lettuce). Cada huarique se guarda como un hash, y un set por celda H3 contiene los identificadores de los huariques de esa celda. La búsqueda consulta los sets de todas las celdas del k-ring, evitando comparar contra todos los huariques (QA-2).

**H3 Adapter**

- **UberH3IndexService** implementa IH3IndexService con la librería Uber H3. Calcula la celda de unas coordenadas y su k-ring.

**Persistence**

La información se almacena en Redis, en una instancia serverless de Upstash. Es una base propia del contexto, independiente de las de los demás servicios. Se mantiene sincronizada mediante los eventos de Business y se puede reconstruir a partir de ellos.

**Event Integration**

Radar consume los eventos publicados por Business mediante RabbitMQ administrado en CloudAMQP, de acuerdo con el Context Mapping.

- **BusinessEventListener** (**@RabbitListener**) escucha una cola durable propia del contexto y deriva cada evento al handler correspondiente.

- **RabbitMQConfig** declara el binding de la cola de Radar a los eventos **HuariqueRegistered, HuariqueUpdated, LocationUpdated y OpeningStatusChanged.** También declara una Dead Letter Queue para mensajes fallidos.

- Si Radar está caído, los eventos quedan en la cola persistente y se procesan al reiniciar (QA-3). Como el upsert es idempotente, un reenvío no genera duplicados.

- Antes de aplicar un evento, el listener compara su version con la almacenada en el hash del huarique; si es menor o igual, lo descarta y confirma el mensaje. Así, un reenvío del Outbox o una nueva entrega de RabbitMQ no genera efectos duplicados ni sobrescribe datos más recientes. El ack manual se envía solo después de actualizar Redis; si el procesamiento falla tres veces, el mensaje pasa a la Dead Letter Queue para su revisión.

**Configuración y salud**

- **RedisConfig** y **RabbitMQConfig** toman las URLs (Upstash y CloudAMQP) de variables de entorno.

- Spring Boot Actuator sirve de base para **GET /radar/status**.

### 5.2.6. Bounded Context Software Architecture Component Level Diagrams



### 5.2.7. Bounded Context Software Architecture Code Level Diagrams

#### 5.2.7.1. Bounded Context Domain Layer Class Diagrams



#### 5.2.7.2. Bounded Context Database Design Diagram

#### 5.2.7.2 (contenido del diagrama de claves Redis)
- `radar:cell:{h3Index}` (Set): miembros = huariqueId
- `radar:huarique:{huariqueId}` (Hash): lat: double, lng: double, h3Cell: string, status: ACTIVE | INACTIVE, updatedAt: timestamp

#### 5.2.7.1 (contenido del diagrama de clases)
- RadarSearchService «Domain Service»: +search(coordinates, kRing): List<HuariqueLocation>; publica H3CellCalculated, NearbyHuariquesFound, NoHuariquesFound
- HuariqueLocation «Aggregate Root»: -huariqueId: HuariqueId, -coordinates: GeoCoordinates, -h3Cell: H3Cell, -status: AvailabilityStatus; +updateLocation(coordinates, h3Cell), +changeStatus(status), +isActive(): boolean
- CoverageZone «VO»: -cells: Set<H3Cell>; +contains(cell): boolean
- Distance «VO»: -meters: double
- GeoCoordinates «VO»: -latitude: double, -longitude: double; +distanceTo(other): Distance
- HuariqueId «VO»: -value: String ; H3Cell «VO»: -index: String
- AvailabilityStatus «enum»: ACTIVE, INACTIVE
- IHuariqueLocationRepository: +save(location), +remove(id), +findById(id), +findByCells(cells): List<HuariqueLocation>
- IH3IndexService: +toCell(coordinates): H3Cell, +kRing(cell, k): CoverageZone

### 5.3.2. Interface Layer

La Interface Layer del Bounded Context Business permite la comunicación entre el frontend de Foodly y los servicios responsables de administrar los huariques. Recibe solicitudes HTTP, valida los datos de entrada y delega las operaciones a la Application Layer.

**REST Controllers**

- **HuariqueController:** Gestiona las solicitudes relacionadas con el registro, actualización y consulta de huariques.

- **MenuController:** Gestiona las solicitudes para consultar y actualizar los menús, categorías y platos de los establecimientos.

**REST Endpoints**

- **POST /business-api/huariques:** Permite registrar un nuevo huarique con sus datos generales, ubicación y tipo de cocina.

- **PUT /business-api/huariques/{id}/menu:** Permite actualizar el menú de un huarique existente, incluyendo sus categorías, platos y precios.

Adicionalmente, los controladores permiten consultar la información registrada de los huariques y sus menús mediante operaciones REST.

**Data Transfer Objects (DTOs)**

- **Datos del huarique:** Contienen el nombre, dirección, tipo de cocina, ubicación, rango de precios y demás información necesaria para registrar o actualizar un establecimiento.

### 5.3.4. Infrastructure Layer

La Infrastructure Layer del Bounded Context Business implementa los mecanismos técnicos necesarios para almacenar la información de los huariques, gestionar sus imágenes y comunicar cambios a otros servicios. Su función es proporcionar las implementaciones concretas de persistencia e integración utilizadas por las capas superiores.

**Repositories**

- **BusinessRepository:** Componente encargado de implementar la persistencia de la información de los huariques y sus menús, atendiendo las operaciones solicitadas por los servicios de aplicación.

**Persistence**

Business utiliza MongoDB como tecnología de persistencia documental para almacenar la información de los establecimientos y su catálogo gastronómico. Esto permite manejar estructuras compuestas por menús, categorías y platos, manteniendo los datos bajo la responsabilidad del propio contexto.

**External Services**

- **ImageStorageAdapter:** Gestiona, a través del Integration System (DD-07), la integración con Cloudinary para almacenar y recuperar fotografías de los huariques y sus platos, evitando que el dominio dependa directamente de este servicio externo.

**Event Integration**

- **BusinessEventPublisher:** Publica eventos relacionados con los cambios realizados en los huariques y sus menús mediante RabbitMQ.

Entre los eventos relevantes se encuentran:

- **HuariqueRegistered (HuariqueRegistrado en el Event Storming):** Se produce cuando se registra un nuevo establecimiento.

- **HuariqueUpdated (PerfilDelHuariqueActualizado):** Se produce cuando se modifican los datos del establecimiento.

- **MenuUpdated (MenuActualizado):** Se produce cuando cambia la información del menú.

- **OpeningStatusChanged (EstadoAbiertoCerradoCambiado):** Se produce cuando se modifica el estado operativo del huarique.

- **LocationUpdated:** Se produce cuando el dueño modifica las coordenadas del huarique; Radar lo utiliza para recalcular su celda H3.

Estos eventos permiten informar a otros contextos, especialmente Radar y Community, sobre los cambios que necesitan conocer, evitando el acceso directo a la base de datos de Business.

**Publicación confiable (Transactional Outbox, DD-08):** BusinessEventPublisher no publica directamente en RabbitMQ. Al guardar un cambio, Business escribe en una misma transacción el documento del huarique y un registro en la colección outbox (MongoDB admite transacciones multidocumento en un replica set, como el clúster de Atlas). Un proceso OutboxRelay lee periódicamente los registros pendientes, los publica con publisher confirms y los marca como enviados solo cuando el broker confirma la recepción; si la publicación falla, se reintenta en el siguiente ciclo. Cada evento incluye eventId, huariqueId, version y occurredAt, para que los consumidores puedan descartar duplicados y eventos desordenados.

**Configuration**

La infraestructura administra las configuraciones necesarias para conectarse con la base de datos, Cloudinary y el broker de mensajería. De esta manera, los servicios de aplicación y las reglas del dominio permanecen desacoplados de las tecnologías externas.

### 5.5.2. Interface Layer

La Interface Layer expone los endpoints REST necesarios para que los exploradores soliciten mesas y para que los administradores de los huariques puedan revisar, aprobar o rechazar dichas solicitudes.

**Controllers**

- ReservationController: Gestiona las operaciones de reservas para comensales y dueños. Expone los siguientes endpoints:

  - POST /reservation-api/reservations: Permite a un explorador autenticado solicitar una nueva reserva.

  - GET /reservation-api/reservations/huarique/{huariqueId}: Permite al dueño del huarique consultar el listado de solicitudes de reserva recibidas.

  - PUT /reservation-api/reservations/{reservationId}/status: Permite al dueño aprobar (APPROVED) o rechazar (REJECTED) una solicitud pendiente.

**Resources (DTOs)**

- CreateReservationResource: Contiene el identificador del huarique, fecha, hora y número de comensales para la solicitud.

- ReservationResponseResource: Representa la información detallada de una reserva, incluyendo su identificador, estado actual, fecha, hora y datos del comensal.

- UpdateReservationStatusResource: Contiene el nuevo estado que se desea aplicar a la solicitud de reserva.

**Validaciones y Respuestas**

La capa valida que los campos obligatorios de fecha, hora y comensales estén presentes. Devuelve respuestas HTTP 201 (Created) al registrar con éxito una reserva, HTTP 200 (OK) en las consultas o actualizaciones, HTTP 400 (Bad Request) ante datos inconsistentes, y HTTP 401/403 si el usuario no cuenta con la autenticación o el rol adecuado administrado por IAM.

### 5.5.3. Application Layer

La Application Layer coordina los casos de uso transaccionales del proceso de reservas, actuando como intermediario entre los controladores HTTP y las reglas de dominio.

**Commands y Command Handlers**

- CreateReservationCommand / CreateReservationCommandHandler: Valida la disponibilidad inicial, crea la entidad Reservation en estado pendiente mediante el dominio y la persiste a través de IReservationRepository.

- UpdateReservationStatusCommand / UpdateReservationStatusCommandHandler: Permite modificar el estado de la reserva (aprobar/denegar) tras la validación de identidad del administrador del local.

**Queries y Query Handlers**

- GetReservationsByHuariqueQueryHandler: Recupera el listado de solicitudes asociadas a un establecimiento para alimentar la bandeja de gestión del dueño.

- GetReservationsByExplorerQueryHandler: Consulta el historial de reservas realizadas por un explorador gastronómico específico.

**Outbound Ports**

- IBusinessValidationClient: Puerto de salida para consultar de forma síncrona o mediante eventos la existencia, vigencia y horarios del huarique en el contexto Business.

### 5.5.4. Infrastructure Layer

La Infrastructure Layer implementa los contratos definidos en el dominio y gestiona la persistencia transaccional y la comunicación asíncrona.

**Repositorios y Persistencia**

- ReservationRepository: Implementa IReservationRepository utilizando Spring Data JPA sobre una base de datos relacional dedicada (PostgreSQL), garantizando el cumplimiento de transacciones ACID requeridas para la consistencia operativa de las reservas.

**Event Integration**

- Implementa la integración mediante RabbitMQ (CloudAMQP) para publicar ReservaAceptada cuando el dueño aprueba una reserva. Community consume este evento para habilitar la reseña del explorador (DD-09). Messaging no consume eventos de Reservation: consulta el estado de la reserva mediante REST, según el Context Mapping (4.2.5).

## 5.6. Bounded Context: Messaging

El bounded context Messaging administra la experiencia conversacional de Foodly: recibe lo que el Explorador Gastronómico le escribe a Lía, interpreta su intención en lenguaje natural y coordina la respuesta con los contextos Business, Radar y Reservation. Es un subdominio de soporte (Bounded Context Canvas, Sección 4.2.4) cuya responsabilidad es la conversación; no decide reglas de menús, ubicaciones ni reservas, que pertenecen a los contextos proveedores. Se implementa como microservicio Spring Boot 3.x sobre Java 17, persiste el historial conversacional en MySQL y desacopla a n8n y OpenAI API mediante una capa Anti-Corruption Layer, según el Context Mapping de la Sección 4.2.5.

### 5.6.1. Domain Layer

La Domain Layer de Messaging contiene el núcleo de la capacidad conversacional: la conversación entre el explorador y Lía, los mensajes intercambiados y la intención que se extrae de cada pedido (una recomendación, la consulta de un huarique o una solicitud de reserva). Las reglas de esta capa no dependen de n8n, OpenAI ni de la base de datos.

**Aggregate Roots**

**Sesiones de chat** representa la conversación de un Explorador Gastronómico con Lía. Contiene el identificador del explorador (emitido por IAM), su estado (activa o cerrada), las fechas de inicio y del último mensaje, y la lista de mensajes intercambiados. Es el único punto de entrada para agregar mensajes: solo una conversación activa acepta nuevos mensajes y cada mensaje del explorador o de Lía se registra a través del agregado, de modo que el historial siempre quede ordenado cronológicamente.

**Entities**

- **Message** representa un mensaje entrante (del explorador) o saliente (de Lía). Contiene el emisor, el contenido, la intención detectada, la fecha de envío y, cuando el mensaje originó una solicitud de reserva, la referencia a la reserva creada por el contexto Reservation.

**Value Objects**

- **MessageContent**: texto del mensaje. No puede estar vacío ni exceder la longitud máxima definida para el chat.

- **UserIntent**: significado extraído de un mensaje en lenguaje natural (término del Ubiquitous Language). Agrupa el tipo de intención, el antojo, el presupuesto, la ubicación, la cantidad de personas y la fecha y hora solicitadas. Indica si está completa y qué contexto le falta.

- **ConversationId**, **MessageId**: identificadores propios del contexto.

- **ExplorerId**, **HuariqueId**, **ReservationId**: identificadores emitidos por IAM, Business y Reservation. Messaging solo los referencia; no almacena los datos de esos contextos.

- **SenderType** (EXPLORER, LIA), **ConversationStatus** (ACTIVE, CLOSED) e **IntentType** (RECOMMENDATION_REQUEST, HUARIQUE_INQUIRY, RESERVATION_REQUEST, UNKNOWN): enumeraciones del dominio.

**Repository Interfaces**

**IConversationRepository** define los contratos para guardar una conversación, obtenerla por identificador, recuperar la conversación activa de un explorador y listar todas sus conversaciones.

**Ports (Anti-Corruption Layer)**

**ILiaAgentGateway** es el puerto mediante el cual el dominio solicita la interpretación de un mensaje sin conocer a n8n ni a OpenAI API. El dominio solo expresa qué necesita (interpretar la intención de un contenido dentro de un contexto conversacional); la implementación concreta pertenece a la Infrastructure Layer.

**Domain Services**

**IntentRoutingService** decide, a partir de una UserIntent, a qué contexto debe delegarse la solicitud: RECOMMEND_VIA_RADAR (recomendación), QUERY_BUSINESS (consulta de menú, precios u horario), FORWARD_TO_RESERVATION (solicitud de reserva completa) o ASK_CLARIFICATION (falta contexto). Concentra la regla de enrutamiento para que no se disperse en la capa de aplicación.

**Business Rules**

Las reglas provienen de las decisiones de negocio del Bounded Context Canvas de Messaging:

- Primero se debe interpretar el lenguaje natural del mensaje antes de decidir cualquier acción.

- Las recomendaciones usan la ubicación y las preferencias del explorador.

- Las solicitudes de reserva completas se reenvían automáticamente a Reservation; Messaging no valida horarios ni capacidad.

- Si falta contexto (por ejemplo fecha, hora o cantidad de personas), Lía debe pedir la aclaración correspondiente.

- Las respuestas de Lía deben ser claras y conversacionales, y un mensaje no puede estar vacío.

### 5.6.2. Interface Layer

La Interface Layer de Messaging expone los endpoints REST con los que los clientes frontend (aplicación web en Vue.js y aplicación móvil en React Native) conversan con Lía y consultan el historial, además del endpoint de callback con el que n8n devuelve la respuesta generada. Todas las rutas se publican a través de Kong API Gateway y los endpoints de usuario exigen el token JWT emitido por IAM (Open Host Service).

**Controllers**

**LiaChatController** recibe los mensajes que el explorador le escribe a Lía. Expone:

- POST /messaging/chat: registra el mensaje del explorador (iniciando una conversación si no existe una activa) y solicita su interpretación. Responde 202 Accepted porque la respuesta de Lía llega de forma asíncrona.

**ConversationController** permite consultar el historial. Expone:

- GET /messaging/conversations/me: lista las conversaciones del explorador autenticado.

- GET /messaging/conversations/{conversationId}/messages: devuelve los mensajes de una conversación, incluidas las respuestas de Lía.

**N8nCallbackController** recibe la respuesta generada por el flujo de n8n. Expone:

- POST /messaging/callbacks/n8n: registra la respuesta de Lía en la conversación. La respuesta de n8n llega primero al Integration System, que la reenvía a este endpoint; no usa el JWT de un usuario y se protege con un secreto compartido entre el Integration System y Messaging.

**Resources**

- **SendMessageResource**: contiene el texto del mensaje, el identificador de la conversación (opcional) y, cuando el explorador comparte su ubicación, la latitud y longitud.

- **MessageResource**: contiene identificador, emisor, contenido, intención detectada, referencia de reserva (si existe) y fecha de envío.

- **ConversationResource**: contiene identificador, estado, fecha de inicio y fecha del último mensaje.

- **LiaCallbackResource**: contiene la conversación, el texto de respuesta de Lía, la intención interpretada, los huariques sugeridos y, si corresponde, la reserva solicitada.

**Validaciones y respuestas**

La capa valida que el mensaje no esté vacío y que las coordenadas, cuando se envían, sean válidas. Devuelve 202 al aceptar un mensaje, 200 en las consultas, 400 ante datos inválidos, 401 sin token válido, 403 si el rol no es Explorador Gastronómico y 404 si la conversación no existe o no pertenece al usuario autenticado.

**5.6.3. Application Layer**

La Application Layer de Messaging coordina los casos de uso conversacionales. Recibe las solicitudes de la Interface Layer, utiliza los elementos del dominio y se comunica con los demás contextos a través de puertos de salida, sin contener lógica de persistencia ni conocer detalles de n8n.

Commands

- SendMessageCommand: contiene explorerId, conversationId (opcional), content y la ubicación del explorador (opcional).

- RegisterLiaReplyCommand: contiene conversationId, el contenido de la respuesta, la intención interpretada, los huariques sugeridos y la reserva solicitada (opcional).

Command Handlers

- SendMessageCommandHandler obtiene o crea la conversación activa, registra el mensaje del explorador en el agregado Conversation, persiste mediante IConversationRepository y solicita la interpretación a través de ILiaAgentGateway.

- RegisterLiaReplyCommandHandler recibe la intención interpretada y utiliza IntentRoutingService para decidir el siguiente paso: consulta a Radar (huariques cercanos por H3), consulta a Business (detalle, menú, precio u horario), reenvío de la solicitud de reserva a Reservation o pedido de aclaración al explorador. Luego registra la respuesta de Lía en la conversación.

Query Handlers

- GetConversationsByExplorerQueryHandler: devuelve las conversaciones del explorador autenticado.

- GetMessagesByConversationQueryHandler: devuelve los mensajes de una conversación, verificando que pertenezca al explorador que consulta.

Outbound Ports

Los siguientes puertos expresan lo que Messaging necesita de los contextos proveedores (relación Customer/Supplier del Context Mapping):

- IBusinessClient: obtiene detalles, menú, precios, horarios y estado de un huarique.

- IRadarClient: obtiene los huariques cercanos a la ubicación del explorador mediante la búsqueda por proximidad H3.

- IReservationClient: solicita una reserva y consulta su estado o confirmación.

Event Handlers

Messaging se coordina con Business, Radar y Reservation mediante llamadas síncronas, por lo que en esta versión no consume eventos de RabbitMQ.

### 5.6.4. Infrastructure Layer

La Infrastructure Layer de Messaging implementa los contratos definidos por el dominio y la aplicación, y administra la persistencia y las integraciones con otros servicios.

Repositories

ConversationRepository implementa IConversationRepository con Spring Data JPA y mapea el agregado Conversation y sus mensajes hacia las tablas conversations y messages.

Persistence

El historial conversacional se almacena en una base de datos MySQL propia del servicio, conforme a la decisión de persistencia políglota de la Sección 4.1. Messaging no comparte tablas con otros contextos: los identificadores de explorador, huarique y reserva se guardan como referencias sin llave foránea física.

Integration with n8n and OpenAI (Anti-Corruption Layer)

N8nLiaAgentGateway implementa ILiaAgentGateway como fachada hacia n8n. Traduce el modelo interno (MessageContent, contexto de la conversación) al formato del webhook de n8n, que invoca a través del Integration System (DD-07), y convierte la respuesta recibida en LiaCallbackResource. n8n orquesta el flujo conversacional y consume OpenAI API para interpretar la intención y generar la respuesta, de modo que un cambio en esos servicios no afecta al modelo del dominio. Si n8n no responde dentro del tiempo de espera configurado, el servicio registra el error y el frontend ofrece al explorador la reserva manual (RF-18).

REST Clients

- BusinessClient, RadarClient y ReservationClient implementan los puertos de salida mediante clientes HTTP hacia cada microservicio y propagan el JWT del explorador.

Security

JwtAuthenticationFilter valida el token emitido por IAM y extrae el identificador y el rol del usuario. El endpoint de callback de n8n se protege por separado con un secreto compartido configurado mediante variables de entorno.

### 5.6.6. Bounded Context Software Architecture Component Level Diagrams

El diagrama de componentes del bounded context Messaging muestra la organización interna del servicio y su relación con los sistemas externos. La Interface Layer recibe las solicitudes provenientes de Kong API Gateway y el callback de n8n; la Application Layer coordina los casos de uso y utiliza los puertos de salida hacia Business, Radar y Reservation; la Domain Layer concentra el agregado Conversation y el servicio de enrutamiento de intenciones; y la Infrastructure Layer implementa la persistencia en MySQL, los clientes REST y la fachada hacia n8n.

Messaging solicita la interpretación de cada mensaje a n8n, que a su vez consume OpenAI API; la respuesta regresa mediante el endpoint de callback. A continuación se presenta el diagrama C4 de nivel Component.



### 5.6.7. Bounded Context Software Architecture Code Level Diagrams

Esta sección presenta el nivel de código del bounded context Messaging: el diagrama de clases de la Domain Layer y el diseño de su base de datos.

#### 5.6.7.1. Bounded Context Domain Layer Class Diagrams

El diagrama de clases muestra el agregado Conversation, la entidad Message, los value objects, las enumeraciones, el servicio de dominio y los contratos (repositorio y puerto hacia el agente de Lía). Conversation compone a sus mensajes y es la única raíz a través de la cual se modifican.



| **Clase**                                                   | **Tipo**             | **Responsabilidad**                                                                    |
|-------------------------------------------------------------|----------------------|----------------------------------------------------------------------------------------|
| Conversation                                                | Aggregate Root       | Agrupa los mensajes de un explorador con Lía y controla su estado.                     |
| Message                                                     | Entity               | Mensaje entrante o saliente, con la intención detectada y la reserva asociada.         |
| UserIntent                                                  | Value Object         | Significado extraído de un mensaje; indica si está completa y qué le falta.            |
| MessageContent                                              | Value Object         | Texto del mensaje; valida que no esté vacío.                                           |
| IntentRoutingService                                        | Domain Service       | Decide a qué contexto delegar según la intención.                                      |
| IConversationRepository                                     | Repository Interface | Contrato de persistencia del agregado.                                                 |
| ILiaAgentGateway                                            | Port                 | Contrato para solicitar la interpretación de un mensaje sin conocer a n8n ni a OpenAI. |
| SenderType, ConversationStatus, IntentType, RoutingDecision | Enumerations         | Estados y tipos permitidos del dominio.                                                |

#### 

#### 5.6.7.2. Bounded Context Database Design Diagram

Messaging utiliza MySQL para almacenar el historial conversacional. El modelo se compone de dos tablas relacionadas uno a muchos: conversations y messages. Los campos explorer_id, huarique_id y reservation_id referencian a otros bounded contexts y no tienen llave foránea física, porque cada contexto es dueño de su propia base de datos.



***Figura. Diagrama de base de datos del bounded context Messaging.***

| **Tabla**     | **Columnas clave**                                                                                             | **Descripción**                                                                                               |
|---------------|----------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| conversations | id (PK), explorer_id, status, started_at, last_message_at                                                      | Una fila por conversación del explorador con Lía.                                                             |
| messages      | id (PK), conversation_id (FK), sender, content, intent_type, intent_data, huarique_id, reservation_id, sent_at | Una fila por mensaje; guarda la intención interpretada y las referencias a huarique y reserva cuando aplican. |


#### 5.6.7.1 (contenido del diagrama de clases)
- Conversation «Aggregate Root»: -id: ConversationId, -explorerId: ExplorerId, -status: ConversationStatus, -startedAt: LocalDateTime, -lastMessageAt: LocalDateTime, -messages: List<Message>; +addExplorerMessage(content): Message, +addLiaReply(content, intent): Message, +linkReservation(messageId, reservationId): void, +close(): void, +isActive(): boolean
- Message «Entity»: -id: MessageId, -sender: SenderType, -content: MessageContent, -intent: UserIntent, -reservationId: ReservationId, -sentAt: LocalDateTime; +isFromLia(): boolean, +hasReservation(): boolean
- UserIntent «VO»: -type: IntentType, -cravings: String, -budgetRange: PriceRange, -location: GeoCoordinates, -partySize: Integer, -requestedAt: LocalDateTime; +isComplete(): boolean, +missingContext(): List<String>
- MessageContent «VO»: -text: String; +isBlank(): boolean, +length(): int
- IConversationRepository: +save(c), +findById(id): Optional<Conversation>, +findActiveByExplorerId(id): Optional<Conversation>, +findAllByExplorerId(id): List<Conversation>
- ILiaAgentGateway «Port»: +requestInterpretation(id, content, ctx): void
- IntentRoutingService «Domain Service»: +route(intent): RoutingDecision
- Enums: SenderType (EXPLORER, LIA), ConversationStatus (ACTIVE, CLOSED), IntentType (RECOMMENDATION_REQUEST, HUARIQUE_INQUIRY, RESERVATION_REQUEST, UNKNOWN), RoutingDecision (RECOMMEND_VIA_RADAR, QUERY_BUSINESS, FORWARD_TO_RESERVATION, ASK_CLARIFICATION)

#### 5.6.7.2 (contenido del diagrama de base de datos, MySQL)
- conversations: id CHAR(36) PK NOT NULL; explorer_id CHAR(36) NOT NULL (ref. IAM); status VARCHAR(10) NOT NULL ACTIVE|CLOSED; started_at DATETIME NOT NULL; last_message_at DATETIME NOT NULL
- messages: id CHAR(36) PK; conversation_id CHAR(36) FK NOT NULL; sender VARCHAR(10) NOT NULL EXPLORER|LIA; content TEXT NOT NULL; intent_type VARCHAR(30) NULL; intent_data JSON NULL (antojo, presupuesto, ubicación…); huarique_id CHAR(36) NULL (ref. Business); reservation_id CHAR(36) NULL (ref. Reservation); sent_at DATETIME NOT NULL
- Relación 1:N conversations → messages. huarique_id, reservation_id y explorer_id no tienen FK física.
