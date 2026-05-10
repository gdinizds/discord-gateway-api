package com.discord.gateway.audit;

import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class GuildLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(GuildLifecycleService.class);

    private static final String UPSERT_REGISTRY_SQL = """
            INSERT INTO gateway.guild_registry
                (guild_id, guild_name, status, bot_permissions, member_count, joined_at, updated_at)
            VALUES
                (:guildId, :guildName, 'ACTIVE'::gateway.guild_status_enum, :botPermissions, :memberCount, NOW(), NOW())
            ON CONFLICT (guild_id) DO UPDATE SET
                guild_name      = EXCLUDED.guild_name,
                status          = 'ACTIVE'::gateway.guild_status_enum,
                bot_permissions = EXCLUDED.bot_permissions,
                member_count    = EXCLUDED.member_count,
                updated_at      = NOW()
            """;

    private static final String UPDATE_LEFT_SQL = """
            UPDATE gateway.guild_registry
            SET status = 'LEFT'::gateway.guild_status_enum, left_at = NOW(), updated_at = NOW()
            WHERE guild_id = :guildId
            """;

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO gateway.guild_event_log (guild_id, event_type, payload, recorded_at)
            VALUES (:guildId, :eventType::gateway.guild_event_type_enum, :payload::jsonb, NOW())
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CircuitBreaker postgresqlCb;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public GuildLifecycleService(NamedParameterJdbcTemplate jdbc,
                                 CircuitBreaker postgresqlCircuitBreaker,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.postgresqlCb = postgresqlCircuitBreaker;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void onJoin(Guild guild) {
        try {
            long botPerms = net.dv8tion.jda.api.Permission.getRaw(guild.getSelfMember().getPermissions());
            postgresqlCb.executeRunnable(() -> {
                jdbc.update(UPSERT_REGISTRY_SQL, registryParams(guild, botPerms));
                jdbc.update(INSERT_EVENT_SQL, eventParams(guild.getId(), "JOINED",
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
                jdbc.update(UPDATE_LEFT_SQL, Map.of("guildId", guildId));
                jdbc.update(INSERT_EVENT_SQL, eventParams(guildId, "LEFT",
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
                jdbc.update(UPSERT_REGISTRY_SQL, registryParams(guild, botPerms));
                jdbc.update(INSERT_EVENT_SQL, eventParams(guild.getId(), eventType, details));
            });
            meterRegistry.counter("discord.gateway.guild.events", "type", eventType).increment();
        } catch (Exception e) {
            log.error("Failed to record guild update [guildId={}, eventType={}]", guild.getId(), eventType, e);
        }
    }

    private MapSqlParameterSource registryParams(Guild guild, long botPerms) {
        return new MapSqlParameterSource()
                .addValue("guildId", guild.getId())
                .addValue("guildName", guild.getName())
                .addValue("botPermissions", botPerms)
                .addValue("memberCount", guild.getMemberCount());
    }

    private MapSqlParameterSource eventParams(String guildId, String eventType, Map<String, Object> payload) {
        try {
            return new MapSqlParameterSource()
                    .addValue("guildId", guildId)
                    .addValue("eventType", eventType)
                    .addValue("payload", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            return new MapSqlParameterSource()
                    .addValue("guildId", guildId)
                    .addValue("eventType", eventType)
                    .addValue("payload", "{}");
        }
    }
}
