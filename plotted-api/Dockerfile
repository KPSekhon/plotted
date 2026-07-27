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
RUN chmod +x gradlew && ./gradlew --no-daemon :plotted-api:dependencies --quiet || true

COPY .editorconfig ./
COPY plotted-api/src plotted-api/src
RUN ./gradlew --no-daemon :plotted-api:bootJar -x test

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# wget is used by the Compose healthcheck; nothing else needs a shell tool.
RUN apk add --no-cache wget \
    && addgroup -S plotted && adduser -S plotted -G plotted
USER plotted

COPY --from=build --chown=plotted:plotted /workspace/plotted-api/build/libs/plotted-api.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# Serial GC and a RAM percentage rather than a fixed heap: Cloud Run gives the
# container a small, fixed memory allowance and scales to zero, so startup time
# matters more than throughput.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
