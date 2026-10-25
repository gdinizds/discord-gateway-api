[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)](https://openjdk.org/)
[![Oracle GraalVM](https://img.shields.io/badge/Oracle%20GraalVM%20(GFTC)-JDK%2025-red?logo=graalvm)](https://www.graalvm.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.9-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![JDA](https://img.shields.io/badge/JDA-5.2.1-blue)](https://github.com/discord-jda/JDA)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka%20%2F%20Redpanda-3--Node%20Cluster-black?logo=apachekafka)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL%20%2F%20pg__partman-18-blue?logo=postgresql)](https://www.postgresql.org/)
[![Storage](https://img.shields.io/badge/S3%20%2F%20Garage-Object--Storage-red)](https://garagehq.deuxfleurs.fr/)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-2.3.0-yellow)](https://resilience4j.readme.io/)

O **Discord Event Gateway** é um serviço de infraestrutura de alto desempenho, reativo e orientado a eventos, projetado para servir como ponte bidirecional entre o Discord e ecossistemas de microsserviços. Construído sobre o **Java 25 com Oracle GraalVM (GFTC)** e **Spring Boot 4**, ele recebe eventos da API de Gateway do Discord via **JDA (Java Discord API)**, normaliza os payloads em envelopes padronizados JSON (com identificadores **UUID v7**) e os transmite com baixíssima latência para tópicos **Apache Kafka / Redpanda** (cluster de 3 nós).

De forma simétrica, o gateway consome intenções de resposta e registros de comandos emitidos por múltiplos bots consumidores, realizando o despacho concorrente de volta à API do Discord através de webhooks, canais diretos e comandos de barra (*slash commands*).

O sistema foi concebido para **altíssima concorrência**, throughput em rajada, isolamento por canal, particionamento temporal com `pg_partman` e suporte nativo a **Oracle GraalVM (GFTC)** (compilação *AOT Native Image* com ZGC generacional).

---

## 🏛️ Arquitetura do Sistema

O gateway atua desacoplando totalmente a lógica de negócio dos bots da conexão persistente com o WebSocket do Discord. O pipeline conta com camadas de sequenciamento ordenado por canal, validação hot-path com zero consultas repetitivas a banco de dados, particionamento nativo no PostgreSQL via `pg_cron` e relay de anexos em storage S3.

```mermaid
graph TD
    subgraph "Discord Infrastructure"
        DW[Discord WebSocket / Gateway]
        D_REST[Discord REST API / Webhooks]
        U[Usuários & Servidores]
    end

    subgraph "Core: discord-event-gateway (Spring Boot 4 + Oracle GraalVM 25)"
        subgraph "Inbound Engine (JDA + Virtual Threads)"
            JDA_EVT[DiscordEventListener]
            ES[EventSequencer - Virtual Threads]
            DOT_P[DotCommandParser]
            AUTH[Response & Channel Filter]
        end

        subgraph "Hot-Path & Data Services"
            ROUTER[EventRouter]
            CACHE[(Bounded In-Memory TTL Cache - 10k Max)]
            RELAY[AttachmentRelayService]
            RECON[StartupReconciliationService]
            HOOK_REG[InteractionHookRegistry]
            BOT_REG[BotMessageRegistry]
            SAFE_SVC[SafeSearchConfigService]
        end

        subgraph "Outbound Engine"
            DISPATCH[ResponseDispatcher]
            VAL[ResponseValidator]
            EXEC[DiscordResponseExecutor]
            CMD_CONS[BotCommandConsumer]
            EPH_REG[EphemeralCommandRegistry]
        end

        subgraph "Auditoria Assíncrona & Transacional"
            IN_LOG[InboundEventLogService]
            OUT_LOG[MessageLogService]
            CMD_LOG[BotCommandPersistenceService]
        end
    end

    subgraph "Messaging: Redpanda 3-Nodes Cluster"
        KP_IN[Kafka: discord.events.*]
        KP_CFG[Kafka: discord.events.config.updated]
        KP_OUT[Kafka: discord.gateway.responses]
        KP_CMD[Kafka: discord.gateway.commands]
        KP_DLT[Kafka: discord.gateway.responses.dlt]
    end

    subgraph "External Storage & Persistence"
        GARAGE[(Garage / S3 Object Storage)]
        PG[(PostgreSQL 18 - Schema gateway)]
        PG_CRON[PostgreSQL Native: pg_partman + pg_cron Hourly Maintenance]
        CONSUL[HashiCorp Consul KV]
        VAULT[HashiCorp Vault Secrets]
    end

    U <--> DW
    DW -->|WebSocket Events| JDA_EVT
    JDA_EVT --> ES
    ES --> DOT_P
    ES --> ROUTER

    ROUTER <-->|Valida Canais em Memória| CACHE
    ROUTER --> RELAY
    RELAY -->|Upload Anexos| GARAGE

    ROUTER -->|Producer Batching LZ4 / Idempotent| KP_IN
    ES -.->|Async Logging| IN_LOG
    IN_LOG --> PG

    KP_IN -->|Consumo pelos Bots| BOTS[Ecossistema de Bots / Microsserviços]
    BOTS -->|Respostas| KP_OUT
    BOTS -->|Declaração de Comandos| KP_CMD

    SAFE_SVC -->|1. Persiste DB| PG
    SAFE_SVC -->|2. Transação Commitada -> Publica Evento| KP_CFG
    KP_CFG -->|Notificação de Configuração| BOTS

    KP_OUT --> DISPATCH
    DISPATCH --> VAL
    VAL -->|Válido| EXEC
    VAL -.->|Inválido / Falha| KP_DLT
    EXEC <-->|Recupera Hook| HOOK_REG
    EXEC <-->|Recupera Mensagem Original| BOT_REG
    EXEC -->|RestAction Queue| D_REST
    DISPATCH -.->|Async Audit| OUT_LOG
    OUT_LOG --> PG

    KP_CMD --> CMD_CONS
    CMD_CONS --> EPH_REG
    CMD_CONS -->|Upsert Slash Commands| D_REST
    CMD_CONS -.->|Audit| CMD_LOG
    CMD_LOG --> PG

    RECON -->|Audit Inicial / Guild Sync| PG
    RECON -->|Upsert /config| D_REST
    PG_CRON -.->|Hourly Maintenance / Pruning| PG
```

---

## ⚡ Fluxo de Execução Ponta a Ponta

Cada evento de entrada e cada comando de resposta percorre um fluxo desacoplado, sem bloqueio de I/O de rede no loop principal:

```mermaid
sequenceDiagram
    autonumber
    actor User as Usuário (Discord)
    participant JDA as JDA (WebSocket)
    participant Seq as EventSequencer (Virtual Thread)
    participant Router as EventRouter & Cache
    participant S3 as Garage S3 Relay
    participant Kafka as Redpanda (3 Brokers)
    participant Bot as Bot Consumidor
    participant Dispatcher as ResponseDispatcher
    participant Executor as DiscordResponseExecutor

    rect rgb(240, 248, 255)
    Note over User,Router: 1. Recepção Inbound & Sequenciamento
    User->>JDA: Envia Mensagem / Interação / Comando
    JDA->>Seq: Dispara evento no callback pool (Virtual Threads)
    Seq->>Seq: Enfileira execução encadeada por channelId (ordem garantida)
    end

    rect rgb(255, 250, 240)
    Note over Seq,S3: 2. Roteamento Zero-DB & Relay de Anexos
    Seq->>Router: Valida canal permitido via cache concorrente delimitado (10k max)
    alt Canal Não Permitido
        Router-->>User: Retorna aviso efêmero / descarta evento
    end
    opt Mensagem possui Anexos
        Router->>S3: Download do Discord CDN e upload para Garage S3 (cache de limites via GuildConfig)
        S3-->>Router: Retorna URLs internas de mídia hospedada
    end
    end

    rect rgb(245, 255, 245)
    Note over Router,Kafka: 3. Normalização e Publicação Idempotente
    Router->>Router: Gera Correlation ID (UUID v7) e monta Envelope JSON
    opt Slash Command / Botão / Modal
        Router->>JDA: deferReply() automático e registra InteractionHook
    end
    Router->>Kafka: Publica em discord.events.* (chave de partição = guildId, acks=all, idempotence=true)
    end

    rect rgb(255, 245, 245)
    Note over Kafka,Dispatcher: 4. Processamento pelos Bots & Retorno Outbound
    Kafka->>Bot: Consome evento inbound
    Bot->>Bot: Executa IA / Regras de Negócio
    Bot->>Kafka: Publica resposta em discord.gateway.responses
    Kafka->>Dispatcher: Consumo com ack manual imediato e Virtual Threads (concurrency=3)
    end

    rect rgb(250, 240, 255)
    Note over Dispatcher,User: 5. Despacho e Resolução no Discord
    Dispatcher->>Dispatcher: Valida schema do payload
    alt InteractionToken presente
        Dispatcher->>Executor: Resgata InteractionHook ativo no registro
        Executor->>JDA: hook.editOriginal() ou hook.sendMessage()
    else Canal Direto (channelId / messageId)
        Executor->>JDA: channel.sendMessage() com referência de reply
    end
    alt Falha de Execução / Validação
        Dispatcher->>Kafka: Encaminha para discord.gateway.responses.dlt
    end
    Note over Dispatcher: Medição de latência garantida no bloco finally
    JDA->>User: Exibe resposta formatada no Discord
    end
```

---

## 🛠️ Destaques de Engenharia e Resiliência

### 1. PostgreSQL Avançado com Particionamento Nativo (`pg_partman` & `pg_cron`)
- **Particionamento Temporal Diário**: A tabela crítica de auditoria `gateway.message_log` é particionada por data (`PARTITION BY RANGE (recorded_at)`), isolando o alto volume de eventos diários em partições individuais.
- **Manutenção Automatizada Nativa no PostgreSQL**: A execução do `partman.run_maintenance(p_analyze => false)` é gerenciada nativamente pelo `pg_cron` dentro do cluster PostgreSQL (CloudNativePG) a cada 1 hora. Isso remove o acoplamento operacional e a necessidade de tarefas agendadas (`@Scheduled`) no serviço Java.
- **Partition Pruning Otimizado**: As consultas de correlação e idempotência no `MessageLogRepository` aplicam filtro explícito de data (`AND recorded_at >= CURRENT_TIMESTAMP - INTERVAL '7' DAY'`), garantindo que o PostgreSQL examine exclusivamente as partições relevantes dos últimos 7 dias.
- **Estratégia de Índices Avançados (V14)**:
  - **Covering Index**: `idx_message_log_discord_msg_covering` em `(discord_message_id) INCLUDE (correlation_id, version)` permite *Index-Only Scans* sem acesso às páginas de dados da tabela.
  - **Partial Index**: `idx_bot_command_ephemeral_active` indexa unicamente comandos ativos (`WHERE is_deleted = false AND ephemeral = true`), mantendo o índice compacto e ultrarrápido.
  - **Composite Index**: `idx_config_audit_guild_created` em `(guild_id, created_at DESC)` otimiza a listagem de auditoria por guilda.
  - **HOT (Heap-Only Tuple) Optimization**: `fillfactor = 85` na tabela `gateway.guild_registry`, prevenindo a fragmentação de páginas (*table bloat*) durante atualizações frequentes de status de guildas.
- **Tuning HikariCP para PostgreSQL**:
  - Habilitado `reWriteBatchedInserts: true` para agrupamento eficiente de inserts nativos.
  - `leak-detection-threshold: 30000` para identificar precocemente retenções anômalas de conexões.
  - `timeout-per-shutdown-phase: 30s` garantindo *graceful shutdown* seguro do pool e das transações ativas.

### 2. Mensageria de Alta Performance com Redpanda (Cluster de 3 Brokers)
- **Topologia Alinhada de 3 Partições**: Tópicos operam com 3 partições e fator de replicação 3 (`replication-factor: 3`, `min.insync.replicas: 2`). A concorrência dos listeners Kafka (`concurrency: 3`) é perfeitamente espelhada para garantir um consumidor por partição, eliminando threads ociosas ou starvation.
- **Producer Idempotente**: Produtores configurados com `enable.idempotence: true` e `max.in.flight.requests.per.connection: 5`, assegurando garantia *exactly-once semantics* na partição sem risco de reordenação de eventos.
- **Cooperative Sticky Partition Assignor**: Reduz paradas de stop-the-world durante rebalances de grupos de consumidores.

### 3. Hierarquia de SafeSearch & Eventos de Configuração Reativos
- **Configuração Granular por Guilda e Canal**: Comando `/config` expõe opções nativas de `safesearch_guild` e `safesearch_channel` (0: Off, 1: Moderate, 2: Strict).
- **Desacoplamento Transacional Rigoroso**: No [`SafeSearchConfigService.java`](src/main/java/com/discord/gateway/audit/SafeSearchConfigService.java), as operações de persistência e auditoria são executadas via `TransactionOperations` e commitadas no PostgreSQL **antes** da publicação do evento de atualização no Kafka. Conexões de banco não são mantidas abertas durante I/O de rede.
- **Propagação Instantânea**: Mudanças de filtro geram eventos imediatos no tópico `discord.events.config.updated`, permitindo que bots de busca/IA adaptem seus filtros em tempo real.

### 4. Hot-Path Zero-DB com Caches Delimitados & TTL
- **Zero Consultas SQL no Fluxo Crítico**: Configurações de guildas como `ALLOWED_CHANNELS`, `ATTACHMENT_RELAY_ENABLED` e `MAX_ATTACHMENT_SIZE_BYTES` são resolvidas diretamente de caches concorrentes thread-safe com expiração TTL (60s).
- **Proteção contra Memory Leaks (Bounded Caches)**: Todos os caches em memória (`relayEnabledCache`, `channelFilterCache`, `maxAttachmentSizeCache`) contam com teto máximo de **10.000 entradas** e política de expiração/limpeza automática quando a capacidade é atingida.
- **Relay com Cache Centralizado**: [`AttachmentRelayService.java`](src/main/java/com/discord/gateway/relay/AttachmentRelayService.java) delega a resolução de tamanhos ao `GuildConfigService`, evitando acessos diretos ao banco por anexo baixado.

### 5. Sequenciamento de Eventos por Canal (`EventSequencer`)
- **Preservação de Ordem por Canal**: Mensagens consecutivas no mesmo canal são encadeadas de forma assíncrona usando `CompletableFuture` com Virtual Threads, impedindo race conditions na ordem de processamento de comandos sem travar o processamento de outros canais ou guildas.
- **Isolamento de Erros**: Falhas de processamento em uma mensagem não propagam interrupção para as mensagens seguintes.

### 6. Observabilidade e Prevenção de Contenção de Locks
- **Cache no Health Check do Discord**: [`DiscordHealthIndicator.java`](src/main/java/com/discord/gateway/config/DiscordHealthIndicator.java) possui cache volátil de 5 segundos para a contagem de guildas (`jda.getGuilds().size()`), eliminando contenção de sincronização interna do JDA provocada por sondagens frequentes do Kubernetes e Consul.
- **Métricas de Latência Robustas**: No [`ResponseDispatcher.java`](src/main/java/com/discord/gateway/dispatcher/ResponseDispatcher.java), a medição de tempo (`sample.stop(...)`) é executada no bloco `finally`, garantindo métricas confiáveis tanto em casos de sucesso quanto em rejeições de validação ou exceções.

---

## ⚡ Runtime de Alta Performance com Oracle GraalVM (GFTC) e Java 25

A aplicação é preparada para compilação **AOT nativa** ou execução com o compilador **GraalVM JIT** utilizando os binários oficiais do **Oracle GraalVM** sob licença **GFTC (GraalVM Free Terms and Conditions)**:

```dockerfile
# Compilação Multi-Stage no Dockerfile utilizando Oracle GraalVM (GFTC)
FROM container-registry.oracle.com/graalvm/native-image:25 AS native-builder
FROM debian:trixie-slim AS native
```

### Otimizações Habilitadas no Dockerfile e Gradle:
- **Oracle GraalVM (GFTC) Native Image & AOT**:
  - Binário executável C++ ultraleve (~60MB), inicialização em **< 50ms** e consumo de memória em repouso de apenas **~40MB RAM**.
  - Flag `-O3` habilitada no `build.gradle` para nível máximo de otimização de compilador (inlining e loop unrolling agressivos).
  - Suporte a diagnóstico e observabilidade nativa com `--enable-monitoring=heapdump,threaddump,jfr`.
  - Configurações completas de reflexão em [`GraalVmHints.java`](src/main/java/com/discord/gateway/config/GraalVmHints.java) abrangendo classes do Hibernate ORM 7 (`PreFlushEventListener[]`), proxies de log JBoss (`*_$logger`), recursos `i18n.properties`, Jackson e bibliotecas de resiliência.
  - Construtores primários anotados com `@Autowired` para suporte determinístico à geração de código pelo Spring Boot 4 AOT.
- **Oracle GraalVM (GFTC) JIT com Generational ZGC**:
  - `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+UseJVMCICompiler`: Ativa o compilador JIT Graal no lugar do C2.
  - `-Djdk.graal.CompilerConfiguration=enterprise`: Habilita a suíte de otimizações enterprise proprietárias da Oracle para taxa máxima de throughput.
  - `-Djdk.graal.Vectorization=true`: Vetorização SIMD automática para aceleração de serialização JSON e processamento de buffers Netty.
  - `-Djdk.graal.UsePriorityInlining=true`: Inlining guiado por prioridade profunda para otimização de chamadas virtuais e streams reativos.
  - `-XX:+UseZGC`: Coletor ZGC geracional (padrão no JDK 25) com pausas sub-milissegundo (< 1ms), ideal para WebSockets e Kafka.
  - `-XX:MaxRAMPercentage=75.0`: Adequação dinâmica ao limite de memória do container.
- **Java 25 Virtual Threads (Project Loom)**:
  - JDA configurado com `setCallbackPool(Executors.newVirtualThreadPerTaskExecutor())`.
  - Spring Virtual Threads habilitadas (`spring.threads.virtual.enabled: true`) para I/O bloqueante concorrente (JDBC, HTTP, S3 e Kafka) sem exaustão de threads do S.O.

---

## 📡 Tópicos & Contratos de Dados

### Tópicos Inbound e Notificações (Gateway publica, bots consomem)

| Tópico | Evento / Gatilho | Prioridade | Finalidade |
|---|---|:---:|---|
| `discord.events.message.created` | Mensagem em canal de texto | normal | Novas mensagens de usuários |
| `discord.events.message.updated` | Edição de mensagem existente | low | Atualizações de texto/conteúdo |
| `discord.events.message.command` | Mensagem iniciada por prefixo `.` | normal | Comandos legados de texto (ex: `.ia`) |
| `discord.events.interaction.command` | Slash command | normal | Interações de barra disparadas |
| `discord.events.interaction.button` | Clique em botão interativo | normal | Eventos de ActionRow components |
| `discord.events.interaction.modal` | Submissão de modal | normal | Dados enviados via popups |
| `discord.events.guild.member` | Entrada / Saída de membro | low | Gestão de comunidade e boas-vindas |
| `discord.events.guild.updated` | Mudança de nome / Tier | low | Atualização de metadados do servidor |
| `discord.events.config.updated` | Alteração de config (/config) | high | Notificação em tempo real de SafeSearch e parâmetros |

### Tópicos Outbound (Bots publicam, gateway consome)

| Tópico | Finalidade | Tratamento de Erro |
|---|---|---|
| `discord.gateway.responses` | Respostas a mensagens e interações | Redirecionamento para DLT |
| `discord.gateway.responses.dlt` | Dead Letter Topic de respostas rejeitadas ou com falha | Retenção para análise e auditoria |
| `discord.gateway.commands` | Registro e remoção dinâmica de comandos no Discord | Retentativas com log de auditoria |

---

## 📄 Envelopes JSON

### 1. Envelope Inbound (`DiscordEventPayload`)
A chave do registro no Kafka é padronizada como `guild.id`.

```json
{
  "eventType": "MESSAGE_CREATED",
  "correlationId": "018f2c3d-7a1b-7e2c-9d4e-5f6a7b8c9d0e",
  "priority": "normal",
  "guild": {
    "id": "123456789012345678",
    "name": "Comunidade Exemplo",
    "iconUrl": "https://cdn.discordapp.com/icons/..."
  },
  "channelId": "987654321098765432",
  "user": {
    "id": "555444333222111000",
    "username": "usuario",
    "avatarUrl": "https://cdn.discordapp.com/avatars/..."
  },
  "interactionToken": null,
  "messageId": "112233445566778899",
  "version": 1,
  "attachments": [
    "http://garage:3900/discord-attachments/guild-123/img.png"
  ],
  "rawPayload": {
    "content": "Olá bot!",
    "referencedMessage": null
  }
}
```

- `correlationId`: UUID v7 baseado em timestamp UNIX Epoch.
- `interactionToken`: Preenchido em interações (`INTERACTION_*`), obrigatório para respostas via Webhook.
- `version`: Incrementado a cada edição da mensagem original. O `correlationId` é preservado entre versões.
- `attachments`: Lista de URLs resolvidas após upload seguro para o storage S3 / Garage.

### 2. Envelope de Notificação de Configuração (`ConfigUpdatedEvent`)
Publicado no tópico `discord.events.config.updated`:

```json
{
  "eventType": "CONFIG_UPDATED",
  "correlationId": "0194e82b-819a-7b3b-8255-09123456789a",
  "guildId": "123456789012345678",
  "channelId": "987654321098765432",
  "userId": "555444333222111000",
  "settingKey": "safesearch_channel",
  "oldValue": "1",
  "newValue": "2",
  "level": 2,
  "timestamp": "2026-10-17T01:30:00Z"
}
```

### 3. Envelope Outbound de Resposta (`OutboundResponsePayload`)
Publicado pelos bots no tópico `discord.gateway.responses`:

```json
{
  "responseType": "DEFERRED_REPLY",
  "interactionToken": "a1b2c3d4...",
  "correlationId": "018f2c3d-7a1b-7e2c-9d4e-5f6a7b8c9d0e",
  "content": "Aqui está sua resposta processada!",
  "channelId": "987654321098765432",
  "messageId": null,
  "finished": true,
  "embeds": [
    {
      "title": "Status da Análise",
      "description": "Processamento concluído com sucesso.",
      "color": 3066993
    }
  ],
  "attachments": [
    {
      "url": "http://garage:3900/discord-attachments/resultado.png",
      "name": "resultado.png",
      "description": "Gráfico gerado"
    }
  ]
}
```

| `responseType` | Cenário de Uso | Requisitos |
|---|---|---|
| `REPLY` | Envio de mensagem direta ou resposta de interação | `content` ou `embeds` |
| `EPHEMERAL_REPLY` | Resposta visível apenas para o autor | Requer `interactionToken` |
| `DEFERRED_REPLY` | Edição da mensagem inicial após `deferReply()` | Requer `interactionToken` |
| `UPDATE_MESSAGE` | Edição de mensagem existente em canal ou componente | Requer `messageId` |
| `DEFERRED_UPDATE` | Edição após defer em componentes | Requer `messageId` |

### 4. Envelope de Comandos (`BotCommandPayload`)
Publicado no tópico `discord.gateway.commands` para sincronizar comandos com o Discord:

```json
{
  "bot_id": "hotbct-ai",
  "guild_id": "123456789012345678",
  "prefix": "SLASH",
  "name": "consultar",
  "description": "Realiza uma consulta personalizada",
  "parameters": [
    {
      "name": "termo",
      "description": "Palavra-chave para busca",
      "type": "STRING",
      "required": true
    }
  ],
  "ephemeral": true,
  "is_deleted": false
}
```

---

## ⚙️ Variáveis de Ambiente e Configurações

As configurações podem ser injetadas via **Consul KV**, **Vault Agent** (arquivos montados em `/vault/secrets/`) ou diretamente por variáveis de ambiente:

| Variável | Padrão | Descrição |
|---|---|---|
| `DISCORD_BOT_TOKEN` | *(Obrigatório)* | Token de autenticação do bot Discord com intents habilitadas. |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Endereço dos brokers Kafka / Redpanda. |
| `GATEWAY_KAFKA_TOPIC_PARTITIONS` | `3` | Quantidade padrão de partições criadas para os tópicos do gateway. |
| `GATEWAY_KAFKA_TOPIC_REPLICAS` | `3` | Fator de replicação dos tópicos (com `min.insync.replicas: 2`). |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://...` | URL de conexão JDBC com o PostgreSQL 18. |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Usuário do banco de dados. |
| `SPRING_DATASOURCE_PASSWORD` | - | Senha do banco de dados. |
| `HIKARI_MAX_POOL_SIZE` | `10` | Tamanho máximo do pool HikariCP. |
| `HIKARI_MIN_IDLE` | `2` | Mínimo de conexões ociosas no pool HikariCP. |
| `GATEWAY_GARAGE_ENDPOINT` | `http://localhost:3900` | Endpoint do S3 (Garage Storage) para upload de anexos. |
| `GATEWAY_GARAGE_BUCKET` | `discord-attachments` | Bucket destino das mídias baixadas. |
| `GARAGE_ACCESS_KEY` | - | Chave de acesso do Garage S3. |
| `GARAGE_SECRET_KEY` | - | Chave secreta do Garage S3. |
| `SPRING_CLOUD_CONSUL_HOST` | `consul...` | Host do HashiCorp Consul para descoberta e configuração centralizada. |
| `SPRING_CLOUD_CONSUL_PORT` | `8500` | Porta do serviço Consul. |

---

## 🚀 Execução Local e Compilação

### Pré-requisitos
- **Java 25** (Oracle GraalVM JDK 25 recomendado)
- **Docker** ou **Podman**
- Cluster Kafka / Redpanda e PostgreSQL (para perfis completos)

### 1. Executando Localmente (Perfil Sem Infraestrutura)
Sobe a aplicação usando banco H2 em memória e sem necessidade de infraestrutura externa:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

A aplicação ficará disponível na porta `8080` com console H2 em `/h2-console`.

### 2. Executando Testes
```bash
# Executa testes unitários rápidos
./gradlew test --tests "com.discord.gateway.unit.*"

# Executa testes de resiliência e circuit breaker
./gradlew test --tests "com.discord.gateway.integration.CircuitBreakerIT"

# Executar bateria completa com Testcontainers (requer Docker ou Podman)
./gradlew test
```

### 3. Build da Imagem de Container (Docker / Podman)

O [Dockerfile](Dockerfile) multi-stage suporta a compilação nativa ou imagem JVM JIT com Oracle GraalVM (GFTC):

```bash
# 1. Imagem Nativa Oracle GraalVM (Target padrão - ~60MB total):
docker build -t discord-event-gateway .
# ou com Podman:
podman build -t discord-event-gateway .

# 2. Imagem Oracle GraalVM JIT de Alta Performance (Generational ZGC + JVMCI):
docker build --target jvm -t discord-event-gateway:jvm .
# ou com Podman:
podman build --target jvm -t discord-event-gateway:jvm .
```

---

## 📊 Observabilidade, Health Probes e Métricas

O serviço expõe endpoints completos compatíveis com **Prometheus** e **Kubernetes**:

- **Health Probes**: `GET /actuator/health` com validações dedicadas de prontidão (`readiness`) para PostgreSQL, Kafka e o status da conexão do **Discord WebSocket**.
- **Prometheus Metrics**: `GET /actuator/prometheus` expondo contadores, gauges e timers de latência.

### Principais Métricas Exportadas:

| Métrica | Tipo | Descrição |
|---|:---:|---|
| `discord.gateway.events.received` | Counter | Total de eventos capturados do WebSocket por tipo |
| `discord.gateway.events.published` | Counter | Eventos normalizados e publicados com sucesso no Kafka |
| `discord.gateway.events.discarded` | Counter | Eventos descartados (motivos: `channel_filtered`, `attachment_relay_failure`, etc.) |
| `discord.gateway.routing.latency` | Timer | Duração do processamento de roteamento e enriquecimento por evento |
| `discord.gateway.dispatch.total` | Counter | Respostas enviadas ao Discord por tipo e resultado (`success`, `discord_error`) |
| `discord.gateway.dispatch.latency` | Timer | Tempo de resposta e despacho ao Discord (medido de forma garantida via bloco finally) |
| `discord.gateway.dispatch.dead_lettered` | Counter | Mensagens encaminhadas para o tópico de DLT |
| `discord.gateway.sequencer.active_keys` | Gauge | Quantidade de canais com filas de execução ativas no sequenciador |
| `discord.gateway.audit.written` | Counter | Quantidade de registros de auditoria persistidos no banco de dados |
| `discord.gateway.config.safesearch` | Counter | Contagem de alterações de SafeSearch por escopo (`guild`/`channel`) e nível (`0`, `1`, `2`) |
| `discord.gateway.config.updated` | Counter | Parâmetros de guilda atualizados com sucesso (`ALLOWED_CHANNELS`, etc.) |
| `discord.gateway.config.errors` | Counter | Total de falhas ao persistir configurações ou auditorias |
| `discord.gateway.commands.processed` | Counter | Comandos sincronizados com o Discord por status (`success=true/false`) |
