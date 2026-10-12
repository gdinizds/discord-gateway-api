# syntax=docker/dockerfile:1

### --- Stage 1: Build Native Executable with GraalVM Native Image ---
FROM container-registry.oracle.com/graalvm/native-image:25 AS native-builder
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
FROM container-registry.oracle.com/graalvm/jdk:25 AS jvm-builder
WORKDIR /app
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon
COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon -x test

# Extrai dependências para lib/ e nomeia o thin jar explicitamente como app.jar
RUN java -Djarmode=tools -jar build/libs/discord-event-gateway-*.jar extract --application-filename app.jar --destination build/extracted

FROM container-registry.oracle.com/graalvm/jdk:25 AS jvm
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

# 1. Dependências externas (~120MB) - raramente mudam, 100% cacheadas pelo Docker
COPY --chown=app:app --from=jvm-builder /app/build/extracted/lib/ lib/
# 2. Thin application jar (~1MB) - copiado diretamente como arquivo app.jar
COPY --chown=app:app --from=jvm-builder /app/build/extracted/app.jar app.jar

USER app
EXPOSE 8080

# Flags de Alta Performance e Compatibilidade para Oracle GraalVM (GFTC) JIT:
# - JVMCI & Graal JIT: Ativa o compilador GraalVM JIT no lugar do C2 (otimizações de inlining, vetorização e escape analysis são ativas por padrão).
# - TieredCompilation: C1 para inicialização rápida + GraalVM JIT no Tier 4 para pico de processamento.
# - ZGC Generacional: Pausas sub-milissegundo (<1ms), ideal para WebSockets (JDA) e streaming Kafka.
# - Container Memory Ergonomics: 75% da RAM do pod para a Heap, 25% para metaspace/direct buffers (Netty/Kafka).
# - String Deduplication: Economia de memória em JSONs e IDs de gateway.
# - Network/DNS: TTL de 30s para service discovery dinâmico no Kubernetes.
ENTRYPOINT ["java", \
  "-XX:+UnlockExperimentalVMOptions", \
  "-XX:+EnableJVMCI", \
  "-XX:+UseJVMCICompiler", \
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
