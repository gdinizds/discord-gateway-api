package com.discord.gateway.audit;

import com.discord.gateway.domain.GuildEventLog;
import com.discord.gateway.domain.GuildRegistry;
import com.discord.gateway.domain.GuildStatus;
import com.discord.gateway.repository.GuildEventLogRepository;
import com.discord.gateway.repository.GuildRegistryRepository;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class GuildLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(GuildLifecycleService.class);

    private final GuildRegistryRepository guildRegistryRepository;
    private final GuildEventLogRepository guildEventLogRepository;
    private final CircuitBreaker postgresqlCb;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public GuildLifecycleService(GuildRegistryRepository guildRegistryRepository,
                                 GuildEventLogRepository guildEventLogRepository,
                                 CircuitBreaker postgresqlCircuitBreaker,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.guildRegistryRepository = guildRegistryRepository;
        this.guildEventLogRepository = guildEventLogRepository;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void onJoin(Guild guild) {
        try {
            long botPerms = net.dv8tion.jda.api.Permission.getRaw(guild.getSelfMember().getPermissions());
            postgresqlCb.executeRunnable(() -> {
                var existing = guildRegistryRepository.findById(guild.getId());
                if (existing.isPresent()) {
                    existing.get().markActive(guild.getName(), guild.getMemberCount(), botPerms);
                } else {
                    guildRegistryRepository.save(new GuildRegistry(
                            guild.getId(), guild.getName(), GuildStatus.ACTIVE,
                            guild.getMemberCount(), botPerms));
                }
                guildEventLogRepository.save(eventLog(guild.getId(), "JOINED",
                        Map.of("guildId", guild.getId(), "guildName", guild.getName())));
            });
            meterRegistry.counter("discord.gateway.guild.events", "type", "JOINED").increment();
        } catch (Exception e) {
            log.error("Failed to record guild join [guildId={}]", guild.getId(), e);
        }
    }

    @Transactional
    public void onLeave(String guildId, String guildName) {
        try {
            postgresqlCb.executeRunnable(() -> {
                guildRegistryRepository.findById(guildId).ifPresent(r -> r.markLeft());
                guildEventLogRepository.save(eventLog(guildId, "LEFT",
                        Map.of("guildId", guildId, "guildName", guildName)));
            });
            meterRegistry.counter("discord.gateway.guild.events", "type", "LEFT").increment();
        } catch (Exception e) {
            log.error("Failed to record guild leave [guildId={}]", guildId, e);
        }
    }

    @Transactional
    public void onUpdate(Guild guild, String eventType, Map<String, Object> details) {
        try {
            long botPerms = net.dv8tion.jda.api.Permission.getRaw(guild.getSelfMember().getPermissions());
            postgresqlCb.executeRunnable(() -> {
                var existing = guildRegistryRepository.findById(guild.getId());
                if (existing.isPresent()) {
                    existing.get().markActive(guild.getName(), guild.getMemberCount(), botPerms);
                } else {
                    guildRegistryRepository.save(new GuildRegistry(
                            guild.getId(), guild.getName(), GuildStatus.ACTIVE,
                            guild.getMemberCount(), botPerms));
                }
                guildEventLogRepository.save(eventLog(guild.getId(), eventType, details));
            });
            meterRegistry.counter("discord.gateway.guild.events", "type", eventType).increment();
        } catch (Exception e) {
            log.error("Failed to record guild update [guildId={}, eventType={}]", guild.getId(), eventType, e);
        }
    }

    private GuildEventLog eventLog(String guildId, String eventType, Map<String, Object> payload) {
        try {
            return new GuildEventLog(guildId, eventType, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            return new GuildEventLog(guildId, eventType, "{}");
        }
    }
}
