package com.discord.gateway.audit;

import com.discord.gateway.domain.MessageDirection;
import com.discord.gateway.domain.MessageLog;
import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.repository.MessageLogRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class InboundEventLogService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventLogService.class);
    static final int DEFAULT_MAX_IN_FLIGHT = 512;
    private static final long CORRELATION_TTL_MS = 86_400_000;
    private static final Set<String> TRACKED_TYPES = Set.of("MESSAGE_CREATED", "MESSAGE_UPDATED", "MESSAGE_COMMAND");

    private final MessageLogRepository messageLogRepository;
    private final CircuitBreaker postgresqlCb;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ExecutorService auditExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight;
    private final ConcurrentHashMap<String, RecentCorrelation> recentCorrelations = new ConcurrentHashMap<>();

    private record RecentCorrelation(UUID correlationId, int version, long timestamp) {}

    @Autowired
    public InboundEventLogService(MessageLogRepository messageLogRepository,
                                  CircuitBreaker postgresqlCircuitBreaker,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this(messageLogRepository, postgresqlCircuitBreaker, objectMapper, meterRegistry, DEFAULT_MAX_IN_FLIGHT);
    }

    InboundEventLogService(MessageLogRepository messageLogRepository,
                           CircuitBreaker postgresqlCircuitBreaker,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry,
                           int maxInFlight) {
        this.messageLogRepository = messageLogRepository;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.inFlight = new Semaphore(maxInFlight);
    }

    public void log(DiscordEventPayload payload) {
        remember(payload);
        if (!inFlight.tryAcquire()) {
            meterRegistry.counter("discord.gateway.audit.dropped", "direction", "INBOUND").increment();
            log.warn("Audit backlog full, inbound log dropped [eventType={}, correlationId={}]",
                    payload.eventType(), payload.correlationId());
            return;
        }
        try {
            auditExecutor.submit(() -> {
                try {
                    doLog(payload);
                } finally {
                    inFlight.release();
                }
            });
        } catch (RuntimeException e) {
            inFlight.release();
            throw e;
        }
    }

    private void doLog(DiscordEventPayload payload) {
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
        var recent = recentCorrelations.get(discordMessageId);
        if (recent != null) {
            return Optional.of(new CorrelationInfo(recent.correlationId(), recent.version()));
        }
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

    @Scheduled(fixedRate = 600_000)
    public void evictExpiredCorrelations() {
        long cutoff = System.currentTimeMillis() - CORRELATION_TTL_MS;
        recentCorrelations.entrySet().removeIf(e -> e.getValue().timestamp() < cutoff);
    }

    int cachedCorrelations() {
        return recentCorrelations.size();
    }

    private void remember(DiscordEventPayload payload) {
        if (payload.messageId() == null || !TRACKED_TYPES.contains(payload.eventType())) return;
        try {
            recentCorrelations.merge(payload.messageId(),
                    new RecentCorrelation(UUID.fromString(payload.correlationId()), payload.version(),
                            System.currentTimeMillis()),
                    (old, fresh) -> fresh.version() >= old.version() ? fresh : old);
        } catch (IllegalArgumentException e) {
            log.debug("Skipping correlation cache for non-UUID correlationId {}", payload.correlationId());
        }
    }

    @PreDestroy
    public void close() {
        auditExecutor.close();
    }
}
