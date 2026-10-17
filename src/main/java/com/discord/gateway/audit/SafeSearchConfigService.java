package com.discord.gateway.audit;

import com.discord.gateway.config.KafkaTopicsConfig;
import com.discord.gateway.domain.ConfigAuditLog;
import com.discord.gateway.domain.SafeSearchConfig;
import com.discord.gateway.model.ConfigUpdatedEvent;
import com.discord.gateway.repository.ConfigAuditLogRepository;
import com.discord.gateway.repository.SafeSearchConfigRepository;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class SafeSearchConfigService {

    private static final Logger log = LoggerFactory.getLogger(SafeSearchConfigService.class);

    private final SafeSearchConfigRepository configRepository;
    private final ConfigAuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker postgresqlCb;
    private final MeterRegistry meterRegistry;
    private final TransactionOperations transactionOperations;

    public record SafeSearchResult(boolean success, String message) {}

    public SafeSearchConfigService(SafeSearchConfigRepository configRepository,
                                  ConfigAuditLogRepository auditLogRepository,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  CircuitBreaker postgresqlCircuitBreaker,
                                  MeterRegistry meterRegistry,
                                  TransactionOperations transactionOperations) {
        this.configRepository = configRepository;
        this.auditLogRepository = auditLogRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.meterRegistry = meterRegistry;
        this.transactionOperations = transactionOperations != null ? transactionOperations : TransactionOperations.withoutTransaction();
    }

    public SafeSearchConfigService(SafeSearchConfigRepository configRepository,
                                  ConfigAuditLogRepository auditLogRepository,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  CircuitBreaker postgresqlCircuitBreaker,
                                  MeterRegistry meterRegistry) {
        this(configRepository, auditLogRepository, kafkaTemplate, objectMapper, postgresqlCircuitBreaker, meterRegistry, TransactionOperations.withoutTransaction());
    }

    @Transactional(readOnly = true)
    public Optional<Integer> getGuildSafeSearch(String guildId) {
        try {
            return configRepository.findByGuildIdAndChannelId(guildId, "")
                    .map(SafeSearchConfig::getLevel);
        } catch (Exception e) {
            log.warn("Failed to query guild safesearch config for guild {}", guildId, e);
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<Integer> getChannelSafeSearch(String guildId, String channelId) {
        try {
            return configRepository.findByGuildIdAndChannelId(guildId, channelId)
                    .map(SafeSearchConfig::getLevel);
        } catch (Exception e) {
            log.warn("Failed to query channel safesearch config for channel {}", channelId, e);
            return Optional.empty();
        }
    }

    public SafeSearchResult setGuildSafeSearch(String guildId, String userId, int level) {
        if (level < 0 || level > 2) {
            return new SafeSearchResult(false, "Nível de SafeSearch inválido. Valores aceitos: 0 (Off), 1 (Moderate), 2 (Strict).");
        }

        try {
            var existing = configRepository.findByGuildIdAndChannelId(guildId, "");
            String oldValue = existing.map(c -> String.valueOf(c.getLevel())).orElse(null);

            transactionOperations.executeWithoutResult(status -> {
                postgresqlCb.executeRunnable(() -> {
                    if (existing.isPresent()) {
                        existing.get().update(level, userId);
                    } else {
                        configRepository.save(new SafeSearchConfig(guildId, "", level, userId));
                    }
                    auditLogRepository.save(new ConfigAuditLog(
                            guildId, null, userId, "safesearch_guild", oldValue, String.valueOf(level)
                    ));
                });
            });

            publishEvent(guildId, null, userId, "safesearch_guild", oldValue, String.valueOf(level), level);

            meterRegistry.counter("discord.gateway.config.safesearch", "scope", "guild", "level", String.valueOf(level)).increment();
            return new SafeSearchResult(true, "SafeSearch padrão da guilda configurado para: `%s`.".formatted(levelLabel(level)));
        } catch (Exception e) {
            log.error("Failed to update guild safesearch [guild={}]", guildId, e);
            meterRegistry.counter("discord.gateway.config.errors").increment();
            return new SafeSearchResult(false, "Falha ao salvar configuração de SafeSearch. Tente novamente em instantes.");
        }
    }

    public SafeSearchResult setChannelSafeSearch(String guildId, String channelId, String userId, int level) {
        if (level < 0 || level > 2) {
            return new SafeSearchResult(false, "Nível de SafeSearch inválido. Valores aceitos: 0 (Off), 1 (Moderate), 2 (Strict).");
        }

        try {
            var existing = configRepository.findByGuildIdAndChannelId(guildId, channelId);
            String oldValue = existing.map(c -> String.valueOf(c.getLevel())).orElse(null);

            transactionOperations.executeWithoutResult(status -> {
                postgresqlCb.executeRunnable(() -> {
                    if (existing.isPresent()) {
                        existing.get().update(level, userId);
                    } else {
                        configRepository.save(new SafeSearchConfig(guildId, channelId, level, userId));
                    }
                    auditLogRepository.save(new ConfigAuditLog(
                            guildId, channelId, userId, "safesearch_channel", oldValue, String.valueOf(level)
                    ));
                });
            });

            publishEvent(guildId, channelId, userId, "safesearch_channel", oldValue, String.valueOf(level), level);

            meterRegistry.counter("discord.gateway.config.safesearch", "scope", "channel", "level", String.valueOf(level)).increment();
            return new SafeSearchResult(true, "SafeSearch para o canal <#%s> configurado para: `%s`.".formatted(channelId, levelLabel(level)));
        } catch (Exception e) {
            log.error("Failed to update channel safesearch [guild={}, channel={}]", guildId, channelId, e);
            meterRegistry.counter("discord.gateway.config.errors").increment();
            return new SafeSearchResult(false, "Falha ao salvar configuração de SafeSearch. Tente novamente em instantes.");
        }
    }

    private void publishEvent(String guildId, String channelId, String userId,
                              String settingKey, String oldValue, String newValue, int level) {
        try {
            ConfigUpdatedEvent event = new ConfigUpdatedEvent(
                    "CONFIG_UPDATED",
                    UUID.randomUUID().toString(),
                    guildId,
                    channelId,
                    userId,
                    settingKey,
                    oldValue,
                    newValue,
                    level,
                    Instant.now().toString()
            );
            String json = objectMapper.writeValueAsString(event);
            String key = channelId != null ? channelId : guildId;
            kafkaTemplate.send(KafkaTopicsConfig.CONFIG_UPDATED_TOPIC, key, json);
        } catch (Exception e) {
            log.warn("Failed to publish ConfigUpdatedEvent to kafka [guild={}, channel={}, key={}]", guildId, channelId, settingKey, e);
        }
    }

    private String levelLabel(int level) {
        return switch (level) {
            case 0 -> "0: Off (Sem filtro)";
            case 1 -> "1: Moderate (Filtro moderado)";
            case 2 -> "2: Strict (Filtro estrito / padrão)";
            default -> String.valueOf(level);
        };
    }
}
