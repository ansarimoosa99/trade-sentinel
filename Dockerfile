# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy Gradle wrapper and dependency manifests first — Docker layer cache means
# these layers are only rebuilt when build.gradle or settings.gradle changes.
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

# Pre-fetch dependencies (cached layer, skips re-download on code-only changes)
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build the fat JAR (tests skipped — run them in CI separately)
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user — good practice, required by some registries
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

# Render injects PORT; Spring listens on it via SERVER_PORT env var
EXPOSE 8080

# JVM tuning for a low-memory container (Render free tier = 512 MB):
#   -XX:+UseContainerSupport   honour cgroup limits instead of host RAM
#   -XX:MaxRAMPercentage=75    use up to 75% of the container's memory
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
