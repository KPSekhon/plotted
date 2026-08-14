# Plotted API. Build context is the repository root so the Gradle wrapper and
# version catalog are available.

FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

# Dependency resolution is cached separately from source, so a code change does
# not re-download the world.
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY plotted-api/build.gradle.kts plotted-api/
COPY plotted-solver/build.gradle.kts plotted-solver/
RUN chmod +x gradlew && ./gradlew --no-daemon :plotted-api:dependencies --quiet || true

COPY .editorconfig ./
COPY plotted-api/src plotted-api/src
COPY plotted-solver/src plotted-solver/src
# Two artefacts, because the optimiser runs in its own process. `build` on the
# solver also stages its runtime dependencies beside the jar, which is what the
# API puts on the worker's -cp.
RUN ./gradlew --no-daemon :plotted-api:bootJar :plotted-solver:build -x test

# Still a JRE. The API launches the worker with the `java` from its own
# ProcessHandle, which a JRE provides -- the worker needs a runtime, not a
# compiler, so nothing here has to grow to a JDK.
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# wget is used by the Compose healthcheck; nothing else needs a shell tool.
RUN apk add --no-cache wget \
    && addgroup -S plotted && adduser -S plotted -G plotted
USER plotted

COPY --from=build --chown=plotted:plotted /workspace/plotted-api/build/libs/plotted-api.jar app.jar

# The optimiser worker, as a plain jar and a directory of its dependencies rather
# than a second fat jar: OR-Tools unpacks its native libraries from a jar on the
# classpath, and repackaging it is the arrangement most likely to break that.
COPY --from=build --chown=plotted:plotted /workspace/plotted-solver/build/libs solver

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# Absolute, because the API resolves the worker relative to its working
# directory and the default is the Gradle output path used in development.
ENV PLOTTED_OPTIMISER_WORKER_DIRECTORY=/app/solver

# Serial GC and a RAM percentage rather than a fixed heap: Cloud Run gives the
# container a small, fixed memory allowance and scales to zero, so startup time
# matters more than throughput.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
