# syntax=docker/dockerfile:1

# ---- Etapa 1: compilación con Maven ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Primero solo el pom: mientras no cambien las dependencias, esta capa y la caché de Maven se reutilizan.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline

COPY src ./src
# Las pruebas (que usan Testcontainers) se ejecutan fuera de la imagen: aquí solo se empaqueta.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package \
    && cp target/radar-service-*.jar /build/app.jar

# ---- Etapa 2: ejecución con JRE 17 ----
# Se usa una imagen basada en Debian/Ubuntu (glibc) porque la librería H3 trae librerías nativas.
FROM eclipse-temurin:17-jre
# El HEALTHCHECK necesita curl: si la imagen base deja de traerlo, el build falla aquí y no en producción.
RUN command -v curl >/dev/null

# Usuario sin privilegios
RUN groupadd --system --gid 10001 radar \
    && useradd --system --uid 10001 --gid radar --no-create-home --shell /usr/sbin/nologin radar

WORKDIR /app
COPY --from=build --chown=radar:radar /build/app.jar /app/app.jar
USER radar:radar

EXPOSE 8080

# El 503 de /actuator/health (Redis o RabbitMQ caídos) marca el contenedor como unhealthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT:-8080}/actuator/health" || exit 1

# La memoria se ajusta al límite del contenedor.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
