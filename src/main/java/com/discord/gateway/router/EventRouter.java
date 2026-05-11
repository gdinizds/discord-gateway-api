package com.discord.gateway.router;

import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.publisher.EventPublisher;
import com.discord.gateway.repository.GuildConfigRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class EventRouter {

    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    private final TopicRegistry topicRegistry;
    private final EventPublisher eventPublisher;
    private final GuildConfigRepository guildConfigRepository;
    private final MeterRegistry meterRegistry;

    public EventRouter(TopicRegistry topicRegistry,
                       EventPublisher eventPublisher,
                       GuildConfigRepository guildConfigRepository,
                       MeterRegistry meterRegistry) {
        this.topicRegistry = topicRegistry;
        this.eventPublisher = eventPublisher;
        this.guildConfigRepository = guildConfigRepository;
        this.meterRegistry = meterRegistry;
    }

    public boolean route(DiscordEventPayload payload, Runnable ephemeralFallback) {
        return route(payload, ephemeralFallback, null);
    }

    public boolean route(DiscordEventPayload payload, Runnable ephemeralFallback, Map<String, String> headers) {
        var mapping = topicRegistry.get(payload.eventType());
        if (mapping == null) {
            log.warn("Unknown event type discarded [type={}]", payload.eventType());
            meterRegistry.counter("discord.gateway.events.discarded",
                    "type", payload.eventType() != null ? payload.eventType() : "null",
                    "reason", "unknown_type").increment();
            return false;
        }

        meterRegistry.counter("discord.gateway.events.received", "type", payload.eventType()).increment();

        String guildId = payload.guild() != null ? payload.guild().getId() : null;
        if (!isChannelAllowed(guildId, payload.channelId())) {
            log.debug("Channel filtered [guild={}, channel={}]", guildId, payload.channelId());
            return false;
        }

        var routed = new DiscordEventPayload(
                payload.eventType(), payload.correlationId(), mapping.priority(),
                payload.guild(), payload.channelId(), payload.user(),
                payload.interactionToken(), payload.messageId(), payload.version(),
                payload.attachments(), payload.rawPayload());

        var sample = Timer.start(meterRegistry);
        boolean published = eventPublisher.publish(mapping.topic(), routed, ephemeralFallback, headers);
        sample.stop(meterRegistry.timer("discord.gateway.routing.latency", "type", payload.eventType()));
        return published;
    }

    private boolean isChannelAllowed(String guildId, String channelId) {
        if (guildId == null || channelId == null) return true;
        try {
            var config = guildConfigRepository.findByGuildIdAndParam(guildId, GuildParam.ALLOWED_CHANNELS);
            if (config.isEmpty()) return true;
            String value = config.get().getValue();
            if ("*".equals(value)) return true;
            for (String id : value.split(",")) {
                if (channelId.equals(id.strip())) return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to check ALLOWED_CHANNELS for guild {}, allowing by default", guildId, e);
            return true;
        }
    }
}
