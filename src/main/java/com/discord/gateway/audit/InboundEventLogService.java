package com.discord.gateway.audit;

import com.discord.gateway.model.DiscordEventPayload;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class InboundEventLogService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventLogService.class);

    private static final String INSERT_SQL = """
            INSERT INTO gateway.message_log
                (correlation_id, version, direction, event_type, discord_message_id,
                 discord_channel_id, guild_id, discord_user_id, payload, recorded_at)
            VALUES
                (:correlationId, :version, :direction::gateway.message_direction_enum, :eventType,
                 :discordMessageId, :discordChannelId, :guildId, :discordUserId, :payload::jsonb, :recordedAt)
            """;

    private static final String LOOKUP_SQL = """
            SELECT correlation_id, MAX(version) AS max_version
            FROM gateway.message_log
            WHERE discord_message_id = :messageId
            GROUP BY correlation_id
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CircuitBreaker postgresqlCb;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public InboundEventLogService(NamedParameterJdbcTemplate jdbc,
                                  CircuitBreaker postgresqlCircuitBreaker,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void log(DiscordEventPayload payload) {
        try {
            var payloadJson = objectMapper.writeValueAsString(payload);
            var correlationId = UUID.fromString(payload.correlationId());

            var params = new MapSqlParameterSource()
                    .addValue("correlationId", correlationId)
                    .addValue("version", payload.version())
                    .addValue("direction", "INBOUND")
                    .addValue("eventType", payload.eventType())
                    .addValue("discordMessageId", payload.messageId())
                    .addValue("discordChannelId", payload.channelId())
                    .addValue("guildId", payload.guildId() != null ? payload.guildId() : "unknown")
                    .addValue("discordUserId", payload.userId())
                    .addValue("payload", payloadJson)
                    .addValue("recordedAt", OffsetDateTime.now());

            postgresqlCb.executeRunnable(() -> jdbc.update(INSERT_SQL, params));
            meterRegistry.counter("discord.gateway.audit.written", "direction", "INBOUND").increment();
        } catch (Exception e) {
            log.error("Failed to log inbound event [eventType={}, correlationId={}]",
                    payload.eventType(), payload.correlationId(), e);
            meterRegistry.counter("discord.gateway.audit.errors", "direction", "INBOUND").increment();
        }
    }

    public record CorrelationInfo(UUID correlationId, int maxVersion) {}

    public Optional<CorrelationInfo> findCorrelation(String discordMessageId) {
        if (discordMessageId == null) return Optional.empty();
        try {
            List<CorrelationInfo> result = postgresqlCb.executeCallable(() ->
                    jdbc.query(LOOKUP_SQL,
                            Map.of("messageId", discordMessageId),
                            (rs, _) -> new CorrelationInfo(
                                    UUID.fromString(rs.getString("correlation_id")),
                                    rs.getInt("max_version"))));
            return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
        } catch (Exception e) {
            log.warn("Failed to look up correlation for message {}", discordMessageId, e);
            return Optional.empty();
        }
    }
}
