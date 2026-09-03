# ---- Build stage ----
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# Dependency layer — re-resolved only when the build files change
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true

COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system appuser && useradd --system --gid appuser appuser \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/build/libs/tiberbu-cce-emitter-adaptor-1.0.0-SNAPSHOT.jar app.jar

RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT:-8080}/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
