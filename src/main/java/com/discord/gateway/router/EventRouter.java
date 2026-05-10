package com.discord.gateway.router;

import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.publisher.EventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class EventRouter {

    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    private static final String ALLOWED_CHANNELS_SQL = """
            SELECT value FROM gateway.guild_config
            WHERE guild_id = :guildId AND param = 'ALLOWED_CHANNELS'
            """;

    private final TopicRegistry topicRegistry;
    private final EventPublisher eventPublisher;
    private final NamedParameterJdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;

    public EventRouter(TopicRegistry topicRegistry,
                       EventPublisher eventPublisher,
                       NamedParameterJdbcTemplate jdbc,
                       MeterRegistry meterRegistry) {
        this.topicRegistry = topicRegistry;
        this.eventPublisher = eventPublisher;
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
    }

    public boolean route(DiscordEventPayload payload, Runnable ephemeralFallback) {
        var mapping = topicRegistry.get(payload.eventType());
        if (mapping == null) {
            log.warn("Unknown event type discarded [type={}]", payload.eventType());
            meterRegistry.counter("discord.gateway.events.discarded",
                    "type", payload.eventType() != null ? payload.eventType() : "null",
                    "reason", "unknown_type").increment();
            return false;
        }

        if (!isChannelAllowed(payload.guildId(), payload.channelId())) {
            log.debug("Channel filtered [guild={}, channel={}]", payload.guildId(), payload.channelId());
            return false;
        }

        meterRegistry.counter("discord.gateway.events.received", "type", payload.eventType()).increment();

        var routed = new DiscordEventPayload(
                payload.eventType(), payload.correlationId(), mapping.priority(),
                payload.guildId(), payload.channelId(), payload.userId(),
                payload.interactionToken(), payload.messageId(), payload.version(),
                payload.attachments(), payload.rawPayload());

        var sample = Timer.start(meterRegistry);
        boolean published = eventPublisher.publish(mapping.topic(), routed, ephemeralFallback);
        sample.stop(meterRegistry.timer("discord.gateway.routing.latency", "type", payload.eventType()));
        return published;
    }

    private boolean isChannelAllowed(String guildId, String channelId) {
        if (guildId == null || channelId == null) return true;
        try {
            List<String> results = jdbc.query(ALLOWED_CHANNELS_SQL,
                    Map.of("guildId", guildId),
                    (rs, _) -> rs.getString("value"));
            if (results.isEmpty()) return true;
            String config = results.get(0);
            if ("*".equals(config)) return true;
            for (String id : config.split(",")) {
                if (channelId.equals(id.strip())) return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to check ALLOWED_CHANNELS for guild {}, allowing by default", guildId, e);
            return true;
        }
    }
}
