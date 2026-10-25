FROM container-registry.oracle.com/graalvm/native-image:25 AS native-builder
WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon

COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle NATIVE_IMAGE_OPTIONS='-J-XX:MaxRAMPercentage=90.0' ./gradlew nativeCompile --no-daemon -x test

FROM debian:trixie-slim AS native
RUN apt-get update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends ca-certificates libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd -g 10001 app && useradd -u 10001 -g 10001 -r -s /bin/false app
WORKDIR /app
COPY --chown=10001:10001 --from=native-builder /app/build/native/nativeCompile/discord-event-gateway /app/discord-event-gateway
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/discord-event-gateway", "-XX:MaximumHeapSizePercent=50"]
