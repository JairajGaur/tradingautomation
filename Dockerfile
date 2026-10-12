# ─────────────────────────────────────────────────────────────────────────────
# Multi-stage build for the trading-automation Spring Boot service.
#
#   Stage 1 (build):  Eclipse Temurin JDK 17 — compiles and packages the boot jar
#                     with the project's Gradle wrapper (Gradle 9.7.1).
#   Stage 2 (runtime): Temurin JRE 17 — slim image that only runs the jar.
#
# Build:  docker build -t tradingautomation .
# Run:    docker run --rm -p 8080:8080 --env-file .env tradingautomation
# (or use docker-compose.yml, which wires the .env file and port for you.)
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: build the boot jar ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy the Gradle wrapper first so the wrapper/distribution layer is cached
# independently of source changes.
COPY gradlew ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

# Copy the build scripts next. NOTE: the host gradle.properties is gitignored and
# points at Homebrew JDK paths that don't exist in this image, so .dockerignore
# excludes it — Gradle then uses this image's bundled JDK 17 for the toolchain.
COPY settings.gradle* build.gradle ./

# Warm the dependency cache. This layer is reused as long as the build scripts
# are unchanged, so day-to-day source edits don't re-download dependencies.
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Now copy the source and build the jar.
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ── Stage 2: minimal runtime ─────────────────────────────────────────────────
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# The app sets its own JVM default timezone to America/New_York at startup, but
# aligning the OS/container TZ keeps shell timestamps and any native time in sync.
ENV TZ=America/New_York

# curl is used by the compose healthcheck to probe /api/health.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app --home /app app

# Copy the single boot jar produced by the build stage.
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# JAVA_OPTS lets you pass JVM flags at runtime (e.g. -Xmx) without rebuilding.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
