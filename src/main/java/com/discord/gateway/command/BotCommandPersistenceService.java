package com.discord.gateway.command;

import com.discord.gateway.domain.BotCommand;
import com.discord.gateway.domain.BotCommandLog;
import com.discord.gateway.domain.CommandEventType;
import com.discord.gateway.domain.CommandPrefix;
import com.discord.gateway.model.BotCommandPayload;
import com.discord.gateway.repository.BotCommandLogRepository;
import com.discord.gateway.repository.BotCommandRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotCommandPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(BotCommandPersistenceService.class);

    private final BotCommandRepository commandRepository;
    private final BotCommandLogRepository logRepository;
    private final CircuitBreaker postgresqlCb;
    private final MeterRegistry meterRegistry;

    public BotCommandPersistenceService(BotCommandRepository commandRepository,
                                        BotCommandLogRepository logRepository,
                                        CircuitBreaker postgresqlCircuitBreaker,
                                        MeterRegistry meterRegistry) {
        this.commandRepository = commandRepository;
        this.logRepository = logRepository;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public BotCommand upsert(BotCommandPayload payload) {
        var prefix = CommandPrefix.valueOf(payload.prefix());
        var guildId = payload.resolvedGuildId();

        try {
            return postgresqlCb.executeCallable(() -> {
                var existing = commandRepository.findByGuildIdAndPrefixAndName(
                        guildId, prefix, payload.name());
                if (existing.isPresent()) {
                    var cmd = existing.get();
                    cmd.update(payload.description(), serializeParameters(payload), payload.isDeleted());
                    return cmd;
                }
                return commandRepository.save(new BotCommand(
                        guildId, prefix,
                        payload.name(), payload.description(), serializeParameters(payload)));
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to upsert bot command", e);
        }
    }

    @Transactional
    public void saveResult(BotCommand command, CommandEventType eventType,
                           String discordCmdId, boolean success, String error) {
        postgresqlCb.executeRunnable(() -> {
            if (discordCmdId != null) {
                command.setDiscordCmdId(discordCmdId);
            }
            logRepository.save(new BotCommandLog(
                    command, eventType, command.getGuildId(), success, error));
        });
        meterRegistry.counter("discord.gateway.commands.processed",
                "success", String.valueOf(success)).increment();
    }

    private String serializeParameters(BotCommandPayload payload) {
        if (payload.parameters() == null || payload.parameters().isEmpty()) return "[]";
        try {
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(payload.parameters());
        } catch (Exception e) {
            log.warn("Failed to serialize parameters for command {}", payload.name());
            return "[]";
        }
    }
}
