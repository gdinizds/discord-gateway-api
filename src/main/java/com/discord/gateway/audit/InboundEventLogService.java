package com.discord.gateway.audit;

import com.discord.gateway.domain.MessageDirection;
import com.discord.gateway.domain.MessageLog;
import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.repository.MessageLogRepository;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class InboundEventLogService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventLogService.class);

    private final MessageLogRepository messageLogRepository;
    private final CircuitBreaker postgresqlCb;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public InboundEventLogService(MessageLogRepository messageLogRepository,
                                  CircuitBreaker postgresqlCircuitBreaker,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.messageLogRepository = messageLogRepository;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void log(DiscordEventPayload payload) {
        try {
            var payloadJson = objectMapper.writeValueAsString(payload);
            var correlationId = UUID.fromString(payload.correlationId());
            var entry = new MessageLog(
                    correlationId, payload.version(), MessageDirection.INBOUND,
                    payload.eventType(), payload.messageId(), payload.channelId(),
                    payload.guild() != null ? payload.guild().getId() : "unknown",
                    payload.user() != null ? payload.user().getId() : null, payloadJson);

            postgresqlCb.executeRunnable(() -> messageLogRepository.save(entry));
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
            var rows = postgresqlCb.executeCallable(() ->
                    messageLogRepository.findCorrelationByDiscordMessageId(discordMessageId));
            if (rows.isEmpty()) return Optional.empty();
            Object[] row = rows.get(0);
            return Optional.of(new CorrelationInfo(
                    UUID.fromString(row[0].toString()),
                    ((Number) row[1]).intValue()));
        } catch (Exception e) {
            log.warn("Failed to look up correlation for message {}", discordMessageId, e);
            return Optional.empty();
        }
    }
}
