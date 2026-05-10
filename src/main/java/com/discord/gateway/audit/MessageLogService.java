package com.discord.gateway.audit;

import com.discord.gateway.domain.MessageDirection;
import com.discord.gateway.domain.MessageLog;
import com.discord.gateway.model.DispatchResult;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.repository.MessageLogRepository;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class MessageLogService {

    private static final Logger log = LoggerFactory.getLogger(MessageLogService.class);

    private final MessageLogRepository messageLogRepository;
    private final CircuitBreaker postgresqlCb;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public MessageLogService(MessageLogRepository messageLogRepository,
                             CircuitBreaker postgresqlCircuitBreaker,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.messageLogRepository = messageLogRepository;
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

            var entry = new MessageLog(
                    correlationId, 1, MessageDirection.OUTBOUND,
                    payload.responseType(), result.discordMessageId(), result.discordChannelId(),
                    guildId != null ? guildId : "unknown", userId, auditPayload);

            postgresqlCb.executeRunnable(() -> messageLogRepository.save(entry));
            meterRegistry.counter("discord.gateway.audit.written", "direction", "OUTBOUND").increment();
        } catch (Exception e) {
            log.error("Failed to record dispatch in message_log — dispatch not blocked [responseType={}]",
                    payload.responseType(), e);
            meterRegistry.counter("discord.gateway.audit.errors", "direction", "OUTBOUND").increment();
        }
    }
}
