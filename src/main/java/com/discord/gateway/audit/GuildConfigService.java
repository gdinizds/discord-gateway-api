package com.discord.gateway.audit;

import com.discord.gateway.domain.GuildConfig;
import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.repository.GuildConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class GuildConfigService {

    private static final Logger log = LoggerFactory.getLogger(GuildConfigService.class);
    private static final long CACHE_TTL_MS = 60_000;

    private static final int MAX_CACHE_ENTRIES = 10_000;
    private record CachedBool(boolean value, long expiryTimeMs) {}
    private record CachedLong(long value, long expiryTimeMs) {}

    private final GuildConfigRepository repository;
    private final CircuitBreaker postgresqlCb;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, CachedBool> relayEnabledCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedLong> maxAttachmentSizeCache = new ConcurrentHashMap<>();

    public record ConfigResult(boolean success, String message) {}

    public GuildConfigService(GuildConfigRepository repository,
                              CircuitBreaker postgresqlCircuitBreaker,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.meterRegistry = meterRegistry;
    }

    private <K, V> void evictIfFull(ConcurrentHashMap<K, V> cache) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
    }

    @Transactional(readOnly = true)
    public boolean isRelayEnabled(String guildId) {
        long now = System.currentTimeMillis();
        var cached = relayEnabledCache.get(guildId);
        if (cached != null && now <= cached.expiryTimeMs()) {
            return cached.value();
        }
        try {
            boolean enabled = repository.findByGuildIdAndParam(guildId, GuildParam.ATTACHMENT_RELAY_ENABLED)
                    .map(c -> "true".equals(c.getValue()))
                    .orElse(true);
            evictIfFull(relayEnabledCache);
            relayEnabledCache.put(guildId, new CachedBool(enabled, now + CACHE_TTL_MS));
            return enabled;
        } catch (Exception e) {
            log.warn("Failed to check ATTACHMENT_RELAY_ENABLED for guild {}, allowing by default", guildId, e);
            return true;
        }
    }

    @Transactional(readOnly = true)
    public long resolveMaxAttachmentSize(String guildId, long fallback) {
        if (guildId == null) return fallback;
        long now = System.currentTimeMillis();
        var cached = maxAttachmentSizeCache.get(guildId);
        if (cached != null && now <= cached.expiryTimeMs()) {
            return cached.value();
        }
        try {
            long maxSize = repository.findByGuildIdAndParam(guildId, GuildParam.MAX_ATTACHMENT_SIZE_BYTES)
                    .map(c -> Long.parseLong(c.getValue()))
                    .orElse(fallback);
            evictIfFull(maxAttachmentSizeCache);
            maxAttachmentSizeCache.put(guildId, new CachedLong(maxSize, now + CACHE_TTL_MS));
            return maxSize;
        } catch (Exception e) {
            log.warn("Failed to check MAX_ATTACHMENT_SIZE_BYTES for guild {}, using fallback", guildId, e);
            return fallback;
        }
    }

    @Transactional
    public ConfigResult upsert(String guildId, GuildParam param, String value) {
        String validationError = param.validate(value);
        if (validationError != null) {
            return new ConfigResult(false,
                    "Valor inválido para `%s`: %s.".formatted(param.name(), validationError));
        }

        try {
            postgresqlCb.executeRunnable(() -> {
                var existing = repository.findByGuildIdAndParam(guildId, param);
                if (existing.isPresent()) {
                    existing.get().update(value);
                } else {
                    repository.save(new GuildConfig(guildId, param, value));
                }
            });
            relayEnabledCache.remove(guildId);
            maxAttachmentSizeCache.remove(guildId);
            meterRegistry.counter("discord.gateway.config.updated", "param", param.name()).increment();
            return new ConfigResult(true,
                    "Parâmetro `%s` atualizado para `%s`.".formatted(param.name(), value));
        } catch (Exception e) {
            log.error("Failed to persist guild config [guild={}, param={}]", guildId, param, e);
            meterRegistry.counter("discord.gateway.config.errors").increment();
            return new ConfigResult(false, "Falha ao salvar configuração. Tente novamente em instantes.");
        }
    }
}
