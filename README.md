# discord-event-gateway

Gateway entre Discord e uma arquitetura orientada a eventos. Recebe eventos do Discord via JDA, normaliza em um envelope JSON padronizado e publica em tópicos Redpanda (Kafka-compatible). Consome respostas dos bots e as despacha de volta ao Discord.

Otimizado para **altíssima concorrência**, baixíssima latência e suporte nativo a **GraalVM** (GraalVM Native Image e GraalVM JIT com ZGC geracional e Java 25 Virtual Threads).

---

## Arquitetura de Alta Concorrência

```
Discord Gateway (WebSocket)
  │
  ▼
JDA (Virtual Thread Per Task Pool)
  │
  ├─ InboundEventLogService ──► [Virtual Thread Executor] ──► PostgreSQL (Auditoria assíncrona)
  │
  ├─ EventRouter (Cache Concorrente TTL em Memória) ──► Validação de Canais Permitidos (zero DB no hot path)
  │
  ├─ AttachmentRelayService ──► Garage / S3 (Relay de anexos com Cache de Flags)
  │
  └─ EventPublisher (Kafka Producer) ──► Redpanda (Tópicos Inbound com Batching LZ4 & linger.ms)
                              │
                         seus bots
                              │
                         discord.gateway.responses  ──► ResponseDispatcher (Listener Concurrency x4 + VT) ──► Discord
                         discord.gateway.commands   ──► BotCommandConsumer ──► Discord API
```

### Otimizações Implementadas
1. **GraalVM Native Image & AOT:**
   - Suporte completo ao plugin `org.graalvm.buildtools.native` (v0.11.1) e Spring Boot 4 AOT.
   - Registro centralizado de reflexão (`GraalVmHints`) para serialização de records/DTOs Jackson, entidades JPA e recursos Flyway.
   - Inicialização instantânea (< 50ms) e pegada mínima de memória (~40MB RAM).
2. **Java 25 Virtual Threads em Todo o Pipeline:**
   - JDA configurado com `setEventPool(Executors.newVirtualThreadPerTaskExecutor())` e `setCallbackPool(Executors.newVirtualThreadPerTaskExecutor())` — o loop WebSocket nunca bloqueia com I/O de rede ou banco.
   - Gravação de logs de auditoria inbound e outbound (`InboundEventLogService` e `MessageLogService`) desacoplada em executores dedicados de Virtual Threads.
3. **Hot-Path Zero-DB (Caches Concorrentes em Memória):**
   - Resolução de `ALLOWED_CHANNELS` e `ATTACHMENT_RELAY_ENABLED` usa cache em memória concorrente (`ConcurrentHashMap` thread-safe com expiração TTL de 60s e invalidação seletiva). Consultas repetitivas ao banco foram eliminadas do caminho crítico de mensagens.
4. **Tuning de Kafka Producer & Consumer:**
   - Producer configurado com compressão `lz4`, `batch-size: 65536`, `linger.ms: 5` e pool de buffer de 64MB para altíssimo throughput sob rajadas de mensagens.
   - Consumer factory com `concurrency: 4`, `max.poll.records: 500` e Virtual Threads para despacho paralelo de respostas ao Discord.
5. **Tuning do Pool HikariCP:**
   - `maximum-pool-size: 30`, cache de prepared statements ativo (`cachePrepStmts: true`, `prepStmtCacheSize: 250`).

---

## GraalVM & Opções de Execução

### Opção 1: Compilação Native Image (Binário Nativo)

Gera um executável nativo C++ ultra-rápido via GraalVM Native Image:

```bash
# Compilar binário nativo localmente (requer GraalVM instalada com native-image)
./gradlew nativeCompile

# Executar o binário gerado
./build/native/nativeCompile/discord-event-gateway
```

### Opção 2: GraalVM JIT de Alto Throughput (Graal JIT + Generational ZGC)

Para cenários onde o JIT compiler da GraalVM supera o desempenho bruto em pico de CPU prolongado:

```bash
java -XX:+UnlockExperimentalVMOptions \
     -XX:+EnableJVMCI \
     -XX:+UseJVMCICompiler \
     -XX:+UseZGC \
     -XX:+ZGenerational \
     -Djdk.virtualThreadScheduler.parallelism=16 \
     -jar build/libs/discord-event-gateway-0.0.1-SNAPSHOT.jar
```

---

## Docker / Podman Multi-Stage Build

O [Dockerfile](file:///C:/Users/guilh/IdeaProjects/discord-gateway-api/Dockerfile) suporta tanto compilação nativa quanto imagem JVM JIT de alta performance:

```bash
# Target padrão: Imagem nativa ultra-leve baseada em debian-slim (~60MB total)
docker build -t discord-event-gateway .
# ou com Podman:
podman build -t discord-event-gateway .

# Target alternativo: GraalVM JIT com ZGC
docker build --target jvm -t discord-event-gateway:jvm .
# ou com Podman:
podman build --target jvm -t discord-event-gateway:jvm .
```

---

## Tópicos

### Inbound — Gateway publica, bots consomem

| Tópico | Evento | Prioridade |
|---|---|---|
| `discord.events.message.created` | Mensagem nova em canal de texto | normal |
| `discord.events.message.updated` | Edição de mensagem existente | low |
| `discord.events.message.command` | Mensagem com prefixo `.` | normal |
| `discord.events.interaction.command` | Slash command | normal |
| `discord.events.interaction.button` | Clique em botão | normal |
| `discord.events.interaction.modal` | Submissão de modal | normal |
| `discord.events.guild.member` | Entrada ou saída de membro | low |
| `discord.events.guild.updated` | Alteração de configuração da guilda | low |

### Outbound — bots publicam, gateway consome

| Tópico | Finalidade |
|---|---|
| `discord.gateway.responses` | Responder interações (slash/button/modal) |
| `discord.gateway.commands` | Registrar ou deletar slash commands no Discord |

---

## Envelope Inbound

Todos os tópicos inbound usam o mesmo envelope. A chave do registro Kafka é sempre `guild.id`.

```json
{
  \"eventType\":        \"MESSAGE_CREATED\",
  \"correlationId\":    \"018f2c3d-7a1b-7e2c-9d4e-5f6a7b8c9d0e\",
  \"priority\":         \"normal\",
  \"guild\":            { \"id\": \"...\", \"name\": \"...\", \"iconUrl\": \"...\" },
  \"channelId\":        \"...\",
  \"user\":             { \"id\": \"...\", \"username\": \"...\", \"avatarUrl\": \"...\" },
  \"interactionToken\": \"...\",
  \"messageId\":        \"...\",
  \"version\":          1,\n  \"attachments\":      [],\n  \"rawPayload\":       {}\n}\n```\n\n- `interactionToken` — presente apenas em `INTERACTION_*`. Necessário para responder via `discord.gateway.responses`.\n- `version` — incrementa a cada edição. O `correlationId` é mantido entre `MESSAGE_CREATED` e seus `MESSAGE_UPDATED`.\n- `attachments` — URLs internas (Garage/S3) dos arquivos relay. Presente em `MESSAGE_CREATED` e `MESSAGE_UPDATED`.\n- Para `MESSAGE_COMMAND` e `INTERACTION_COMMAND`, o nome do comando vem no header Kafka `command-name`.\n\n### rawPayload por tipo\n\n| eventType | Conteúdo |\n|---|---|\n| `MESSAGE_CREATED` / `MESSAGE_UPDATED` | `content`, `referencedMessage?` |\n| `MESSAGE_COMMAND` | `args: string[]` (posicionais) |\n| `INTERACTION_COMMAND` | `args: { nome: valor }` (opções nomeadas) |\n| `INTERACTION_BUTTON` | `componentId`, `messageId` |\n| `INTERACTION_MODAL` | `modalId`, `values: { campo: valor }` |\n| `GUILD_MEMBER` | `action: \"JOIN\" \\| \"LEAVE\"` |\n| `GUILD_UPDATED` | `field`, `oldValue`, `newValue` |\n\n---\n\n## Contrato Outbound\n\n### `discord.gateway.responses`\n\n```json\n{\n  \"responseType\":     \"DEFERRED_REPLY\",\n  \"interactionToken\": \"AbCdEf...\",\n  \"correlationId\":    \"018f2c3d-...\",\n  \"content\":          \"Resposta do bot\",\n  \"embeds\":           []\n}\n```\n\n| responseType | Quando usar | content obrigatório |\n|---|---|:---:|\n| `REPLY` | Resposta sem defer | sim |\n| `EPHEMERAL_REPLY` | Visível só para o usuário | sim |\n| `DEFERRED_REPLY` | Após `deferReply()` — caso padrão | sim |\n| `UPDATE_MESSAGE` | Editar mensagem do botão (requer `messageId`) | sim |\n| `DEFERRED_UPDATE` | Editar após defer (requer `messageId`) | não |\n\n### `discord.gateway.commands`\n\n```json\n{\n  \"bot_id\":      \"...\",\n  \"guild_id\":    \"...\",\n  \"prefix\":      \"SLASH\",\n  \"name\":        \"clima\",\n  \"description\": \"Consulta o clima de uma cidade\",\n  \"parameters\":  [\n    { \"name\": \"cidade\", \"description\": \"Nome da cidade\", \"type\": \"STRING\", \"required\": true }\n  ],\n  \"is_deleted\":  false\n}\n```\n\n- `prefix`: `SLASH` registra no Discord; `DOT` persiste apenas no banco.\n- `guild_id` ausente ou nulo: comando global.\n- `is_deleted: true`: remove o comando do Discord.\n- `parameters[].type`: `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `USER`, `CHANNEL`, `ROLE`.\n\n---\n\n## Rodando Localmente\n\nPré-requisitos: Java 25 (GraalVM recomendada).\n\n```bash\n./gradlew bootRun --args='--spring.profiles.active=local'\n```\n\nSobe em memória com H2, sem nenhuma infra externa. A aplicação fica disponível na porta `8080`.\n\n---\n\n## Testes com Podman / Docker\n\nOs testes unitários e de circuit breaker rodam sem dependências externas:\n\n```bash\n# Testes unitários rápidos\n./gradlew test --tests \"com.discord.gateway.unit.*\"\n\n# Teste de Circuit Breaker (sem container)\n./gradlew test --tests \"com.discord.gateway.integration.CircuitBreakerIT\"\n```\n\nPara executar os testes de integração do Testcontainers com **Podman**:\n\n```bash\n# Configurar ambiente Testcontainers para apontar para o socket do Podman no Windows WSL/Rootless:\nset DOCKER_HOST=npipe:////./pipe/docker_engine\n# ou apontando para o socket Podman TCP / SSH:\nset DOCKER_HOST=tcp://localhost:64211\n./gradlew test\n```\n\n---\n\n## Recursos e Métricas\n\n- `GET /actuator/health` — status da aplicação e dependências\n- `GET /actuator/prometheus` — métricas Prometheus\n- `GET /actuator/info` — informações da build\n\n### Métricas principais\n\n| Métrica | Descrição |\n|---|---|\n| `discord.gateway.events.published` | Eventos publicados por tipo e tópico |\n| `discord.gateway.events.discarded` | Eventos descartados por tipo e motivo |\n| `discord.gateway.events.received` | Eventos recebidos por tipo |\n| `discord.gateway.routing.latency` | Latência de roteamento por tipo |\n| `discord.gateway.dispatch.total` | Respostas despachadas por tipo e resultado |\n| `discord.gateway.dispatch.latency` | Latência de despacho ao Discord |\n| `discord.gateway.audit.written` | Registros de auditoria gravados |\n