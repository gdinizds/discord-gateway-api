# syntax=docker/dockerfile:1

### --- Stage 1: Build Native Executable with GraalVM Native Image ---
FROM ghcr.io/graalvm/native-image-community:25 AS native-builder
WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon

COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle ./gradlew nativeCompile --no-daemon -x test

### --- Stage 2: Ultra-lightweight Native Container (Default) ---
FROM debian:bookworm-slim AS native
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --chown=app:app --from=native-builder /app/build/native/nativeCompile/discord-event-gateway /app/discord-event-gateway
USER app
EXPOSE 8080
ENTRYPOINT ["/app/discord-event-gateway"]

### --- Stage 3: GraalVM JIT High-Throughput Alternative ---
FROM ghcr.io/graalvm/jdk-community:25 AS jvm-builder
WORKDIR /app
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon
COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon -x test

# Extrai dependências para lib/ mantendo o thin jar (otimiza Docker layer cache e startup)
RUN java -Djarmode=tools -jar build/libs/discord-event-gateway-*.jar extract --destination build/extracted

FROM ghcr.io/graalvm/jdk-community:25 AS jvm
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

# 1. Dependências externas (~120MB) - raramente mudam, ficam 100% em cache no Docker
COPY --chown=app:app --from=jvm-builder /app/build/extracted/lib/ lib/
# 2. Thin application jar (~1MB) - atualizado rapidamente em cada build
COPY --chown=app:app --from=jvm-builder /app/build/extracted/*.jar app.jar

USER app
EXPOSE 8080

# GraalVM JIT & JVM Performance Arguments:
# - Execução direta em formato JSON array (evita interpretação de shell/sh e bugs de escape de quebra de linha)
# - Suporta opções adicionais em runtime automaticamente via env var JAVA_TOOL_OPTIONS
ENTRYPOINT ["java", \
  "-XX:+UnlockExperimentalVMOptions", \
  "-XX:+EnableJVMCI", \
  "-XX:+UseJVMCICompiler", \
  "-Djdk.graal.TuneInlinerExploration=0.5", \
  "-Djdk.graal.OptimizeLongJumps=true", \
  "-Djdk.graal.RemoveLoopSafepoints=true", \
  "-Djdk.graal.Vectorization=true", \
  "-XX:+TieredCompilation", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:+UseStringDeduplication", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dnetworkaddress.cache.ttl=30", \
  "-Dnetworkaddress.cache.negative.ttl=10", \
  "-Dfile.encoding=UTF-8", \
  "-jar", "app.jar"]
