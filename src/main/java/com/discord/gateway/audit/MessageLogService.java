package com.discord.gateway.audit;

import com.discord.gateway.model.DispatchResult;
import com.discord.gateway.model.OutboundResponsePayload;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class MessageLogService {

    private static final Logger log = LoggerFactory.getLogger(MessageLogService.class);

    private static final String INSERT_SQL = """
            INSERT INTO gateway.message_log
                (correlation_id, version, direction, event_type, discord_message_id,
                 discord_channel_id, guild_id, discord_user_id, payload, recorded_at)
            VALUES
                (:correlationId, :version, :direction::gateway.message_direction_enum, :eventType,
                 :discordMessageId, :discordChannelId, :guildId, :discordUserId, :payload::jsonb, :recordedAt)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CircuitBreaker postgresqlCb;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public MessageLogService(NamedParameterJdbcTemplate jdbc,
                             CircuitBreaker postgresqlCircuitBreaker,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void logOutbound(OutboundResponsePayload payload, DispatchResult result,
                            String guildId, String userId) {
        try {
            var correlationId = payload.correlationId() != null
                    ? UUID.fromString(payload.correlationId())
                    : UUID.randomUUID();

            var auditPayload = objectMapper.writeValueAsString(Map.of(
                    "payload", payload,
                    "result", Map.of(
                            "success", result.success(),
                            "discordMessageId", result.discordMessageId() != null ? result.discordMessageId() : "",
                            "discordError", result.discordError() != null ? result.discordError() : ""
                    )
            ));

            var params = new MapSqlParameterSource()
                    .addValue("correlationId", correlationId)
                    .addValue("version", 1)
                    .addValue("direction", "OUTBOUND")
                    .addValue("eventType", payload.responseType())
                    .addValue("discordMessageId", result.discordMessageId())
                    .addValue("discordChannelId", result.discordChannelId())
                    .addValue("guildId", guildId != null ? guildId : "unknown")
                    .addValue("discordUserId", userId)
                    .addValue("payload", auditPayload)
                    .addValue("recordedAt", OffsetDateTime.now());

            postgresqlCb.executeRunnable(() -> jdbc.update(INSERT_SQL, params));
            meterRegistry.counter("discord.gateway.audit.written", "direction", "OUTBOUND").increment();

        } catch (Exception e) {
            log.error("Falha ao registrar dispatch em message_log — dispatch não bloqueado [responseType={}]",
                    payload.responseType(), e);
            meterRegistry.counter("discord.gateway.audit.errors", "direction", "OUTBOUND").increment();
        }
    }
}
