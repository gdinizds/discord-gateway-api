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

@Service
public class GuildConfigService {

    private static final Logger log = LoggerFactory.getLogger(GuildConfigService.class);

    private final GuildConfigRepository repository;
    private final CircuitBreaker postgresqlCb;
    private final MeterRegistry meterRegistry;

    public record ConfigResult(boolean success, String message) {}

    public GuildConfigService(GuildConfigRepository repository,
                              CircuitBreaker postgresqlCircuitBreaker,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.meterRegistry = meterRegistry;
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
