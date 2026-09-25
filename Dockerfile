# syntax=docker/dockerfile:1

### --- Stage 1: Build Native Executable with GraalVM Native Image ---
FROM ghcr.io/graalvm/native-image-community:25 AS native-builder
WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon

COPY src/ src/
RUN ./gradlew nativeCompile --no-daemon -x test

### --- Stage 2: Ultra-lightweight Native Container (Default) ---
FROM debian:bookworm-slim AS native
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libstdc++6 && rm -rf /var/lib/apt/lists/*
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=native-builder /app/build/native/nativeCompile/discord-event-gateway /app/discord-event-gateway
USER app
EXPOSE 8080
ENTRYPOINT ["/app/discord-event-gateway"]

### --- Stage 3: GraalVM JIT High-Throughput Alternative ---
FROM ghcr.io/graalvm/jdk-community:25 AS jvm-builder
WORKDIR /app
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

FROM ghcr.io/graalvm/jdk-community:25 AS jvm
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=jvm-builder /app/build/libs/discord-event-gateway-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UnlockExperimentalVMOptions", \
  "-XX:+EnableJVMCI", \
  "-XX:+UseJVMCICompiler", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "-Djdk.virtualThreadScheduler.parallelism=16", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
