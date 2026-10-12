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

# Extrai as camadas do Spring Boot JAR para maximizar reutilização do cache do Docker e tempo de inicialização
RUN java -Djarmode=tools -jar build/libs/discord-event-gateway-*.jar extract --layers --launcher --destination build/extracted

FROM ghcr.io/graalvm/jdk-community:25 AS jvm
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

# Camadas copiadas da menor para a maior frequência de alteração
COPY --chown=app:app --from=jvm-builder /app/build/extracted/dependencies/ ./
COPY --chown=app:app --from=jvm-builder /app/build/extracted/spring-boot-loader/ ./
COPY --chown=app:app --from=jvm-builder /app/build/extracted/snapshot-dependencies/ ./
COPY --chown=app:app --from=jvm-builder /app/build/extracted/application/ ./

USER app
EXPOSE 8080

# --- Flags de Alta Performance GraalVM JIT & JVM ---
# 1. Compilação GraalVM JIT (JVMCI):
#    - EnableJVMCI / UseJVMCICompiler: Habilita o compilador Graal JIT no lugar do C2.
#    - TuneInlinerExploration=0.5: Explora mais oportunidades de inlining agressivo para pico de throughput.
#    - OptimizeLongJumps=true: Reduz tamanho de instruções de salto x86_64 (melhor uso de cache de instrução).
#    - RemoveLoopSafepoints=true: Elimina checks de safepoint redundantes em loops contados.
#    - Vectorization=true: Vetorização automática SIMD (acelera serialização/deserialização JSON e buffers de rede).
#    - TieredCompilation: O C1 entrega warmup rápido enquanto o Graal compila métodos quentes para performance máxima.
#
# 2. Garbage Collector & Memória (ZGC Generacional):
#    - UseZGC: Latência de pausa sub-milissegundo (<1ms), essencial para evitar timeouts de WebSocket (JDA) e Kafka.
#    - MaxRAMPercentage=75.0: Aloca 75% da RAM do container para a heap (evita o default de 25% que desperdiça recursos).
#    - InitialRAMPercentage=50.0: Evita custos de expansão contínua de heap durante a subida.
#    - UseStringDeduplication: Elimina strings duplicadas na heap (IDs snowflake, nomes de evento, payloads JSON).
#    - ExitOnOutOfMemoryError: Garante encerramento limpo para o orquestrador (Docker/K8s) reiniciar o container em caso de OOM.
#
# 3. Rede & Sistema:
#    - networkaddress.cache.ttl=30: Limita cache DNS para suportar service discovery dinâmico (Consul, DB, Kafka failovers).
#    - java.security.egd: Geração não-bloqueante de entropia para handshakes SSL/TLS rápidos.
ENV JAVA_OPTS="\
  -XX:+UnlockExperimentalVMOptions \
  -XX:+EnableJVMCI \
  -XX:+UseJVMCICompiler \
  -Djdk.graal.TuneInlinerExploration=0.5 \
  -Djdk.graal.OptimizeLongJumps=true \
  -Djdk.graal.RemoveLoopSafepoints=true \
  -Djdk.graal.Vectorization=true \
  -XX:+TieredCompilation \
  -XX:+UseZGC \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseStringDeduplication \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Dnetworkaddress.cache.ttl=30 \
  -Dnetworkaddress.cache.negative.ttl=10 \
  -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher \"$@\"", "--"]
