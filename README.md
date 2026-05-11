# discord-event-gateway

Gateway entre Discord e uma arquitetura orientada a eventos. Recebe eventos do Discord via JDA, normaliza em um envelope JSON padronizado e publica em tópicos Redpanda (Kafka-compatible). Consome respostas dos bots e as despacha de volta ao Discord.

---

## Arquitetura

```
Discord
  │
  ▼
DiscordEventListener (JDA)
  │
  ├── AttachmentRelayService  →  Garage / S3 (relay de anexos)
  │
  ├── EventRouter  →  filtra canais, aplica prioridade
  │
  └── EventPublisher  →  Redpanda (tópicos inbound)
                              │
                         seus bots
                              │
                         discord.gateway.responses  →  ResponseDispatcher  →  Discord
                         discord.gateway.commands   →  BotCommandConsumer  →  Discord API
```

**Stack:** Java 25 (Virtual Threads), Spring Boot 4, JDA 5, Redpanda, PostgreSQL 17, Garage/S3, Resilience4j, Flyway, Prometheus.

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
  "eventType":        "MESSAGE_CREATED",
  "correlationId":    "018f2c3d-7a1b-7e2c-9d4e-5f6a7b8c9d0e",
  "priority":         "normal",
  "guild":            { "id": "...", "name": "...", "iconUrl": "..." },
  "channelId":        "...",
  "user":             { "id": "...", "username": "...", "avatarUrl": "..." },
  "interactionToken": "...",
  "messageId":        "...",
  "version":          1,
  "attachments":      [],
  "rawPayload":       {}
}
```

- `interactionToken` — presente apenas em `INTERACTION_*`. Necessário para responder via `discord.gateway.responses`.
- `version` — incrementa a cada edição. O `correlationId` é mantido entre `MESSAGE_CREATED` e seus `MESSAGE_UPDATED`.
- `attachments` — URLs internas (Garage/S3) dos arquivos relay. Presente em `MESSAGE_CREATED` e `MESSAGE_UPDATED`.
- Para `MESSAGE_COMMAND` e `INTERACTION_COMMAND`, o nome do comando vem no header Kafka `command-name`.

### rawPayload por tipo

| eventType | Conteúdo |
|---|---|
| `MESSAGE_CREATED` / `MESSAGE_UPDATED` | `content`, `referencedMessage?` |
| `MESSAGE_COMMAND` | `args: string[]` (posicionais) |
| `INTERACTION_COMMAND` | `args: { nome: valor }` (opções nomeadas) |
| `INTERACTION_BUTTON` | `componentId`, `messageId` |
| `INTERACTION_MODAL` | `modalId`, `values: { campo: valor }` |
| `GUILD_MEMBER` | `action: "JOIN" \| "LEAVE"` |
| `GUILD_UPDATED` | `field`, `oldValue`, `newValue` |

---

## Contrato Outbound

### `discord.gateway.responses`

```json
{
  "responseType":     "DEFERRED_REPLY",
  "interactionToken": "AbCdEf...",
  "correlationId":    "018f2c3d-...",
  "content":          "Resposta do bot",
  "embeds":           []
}
```

| responseType | Quando usar | content obrigatório |
|---|---|:---:|
| `REPLY` | Resposta sem defer | sim |
| `EPHEMERAL_REPLY` | Visível só para o usuário | sim |
| `DEFERRED_REPLY` | Após `deferReply()` — caso padrão | sim |
| `UPDATE_MESSAGE` | Editar mensagem do botão (requer `messageId`) | sim |
| `DEFERRED_UPDATE` | Editar após defer (requer `messageId`) | não |

### `discord.gateway.commands`

```json
{
  "bot_id":      "...",
  "guild_id":    "...",
  "prefix":      "SLASH",
  "name":        "clima",
  "description": "Consulta o clima de uma cidade",
  "parameters":  [
    { "name": "cidade", "description": "Nome da cidade", "type": "STRING", "required": true }
  ],
  "is_deleted":  false
}
```

- `prefix`: `SLASH` registra no Discord; `DOT` persiste apenas no banco.
- `guild_id` ausente ou nulo: comando global.
- `is_deleted: true`: remove o comando do Discord.
- `parameters[].type`: `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `USER`, `CHANNEL`, `ROLE`.

---

## Variáveis de Ambiente

| Variável | Descrição |
|---|---|
| `DISCORD_BOT_TOKEN` | Token do bot Discord |
| `DB_URL` | JDBC URL do PostgreSQL |
| `DB_USERNAME` | Usuário da aplicação (DML) |
| `DB_PASSWORD` | Senha do usuário da aplicação |
| `SPRING_FLYWAY_USER` | Usuário de migração (DDL) |
| `SPRING_FLYWAY_PASSWORD` | Senha do usuário de migração |
| `REDPANDA_BOOTSTRAP_SERVERS` | Endereço do broker Redpanda |
| `GARAGE_ENDPOINT` | Endpoint S3-compatible (Garage ou MinIO) |
| `GARAGE_BUCKET` | Bucket para relay de anexos |
| `GARAGE_REGION` | Região do bucket |
| `GARAGE_ACCESS_KEY` | Access key S3 |
| `GARAGE_SECRET_KEY` | Secret key S3 |
| `ATTACHMENT_MAX_SIZE_BYTES` | Limite global de tamanho de anexo (padrão: 26214400 = 25 MB) |

---

## Rodando Localmente

Pré-requisitos: Docker, Java 25.

```bash
# Sobe Postgres, Redpanda e MinIO
DISCORD_BOT_TOKEN=seu-token docker compose up -d

# Executa a aplicação fora do compose (desenvolvimento)
./gradlew bootRun
```

A aplicação sobe na porta `8080`. Redpanda acessível em `localhost:19092`.

Para rodar dentro do compose junto à infraestrutura:

```bash
DISCORD_BOT_TOKEN=seu-token docker compose up --build
```

---

## Testes

```bash
./gradlew test
```

Testes unitários e de integração sem Docker rodam sem dependências externas (H2 + mocks). Os testes de integração com containers (`EventPublishingIT`, `AttachmentRelayIT`, `ResponseDispatchIT`) requerem Docker.

---

## Recursos

- `GET /actuator/health` — status da aplicação e dependências
- `GET /actuator/prometheus` — métricas Prometheus
- `GET /actuator/info` — informações da build

### Métricas principais

| Métrica | Descrição |
|---|---|
| `discord.gateway.events.published` | Eventos publicados por tipo e tópico |
| `discord.gateway.events.discarded` | Eventos descartados por tipo e motivo |
| `discord.gateway.events.received` | Eventos recebidos por tipo |
| `discord.gateway.routing.latency` | Latência de roteamento por tipo |
| `discord.gateway.dispatch.total` | Respostas despachadas por tipo e resultado |
| `discord.gateway.dispatch.latency` | Latência de despacho ao Discord |
| `discord.gateway.audit.written` | Registros de auditoria gravados |

---

## Circuit Breakers

Três circuit breakers independentes protegem as dependências externas:

| Nome | Protege |
|---|---|
| `redpanda` | Publicação de eventos no broker |
| `postgresql` | Leitura e escrita no banco de auditoria |
| `garage` | Upload de anexos no S3 |

Configuração padrão: janela de 10 chamadas, abre com 50% de falhas, aguarda 10s antes de tentar recuperar.

Quando o circuit breaker do Redpanda está aberto, interações (slash/button/modal) recebem uma resposta efêmera de indisponibilidade. Eventos passivos (mensagens, guild) são descartados silenciosamente.
