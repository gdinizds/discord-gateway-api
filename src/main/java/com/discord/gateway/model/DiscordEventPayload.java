package com.discord.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscordEventPayload(
        String eventType,
        String correlationId,
        String priority,
        GuildInfo guild,
        String channelId,
        UserInfo user,
        String interactionToken,
        String messageId,
        int version,
        List<String> attachments,
        Map<String, Object> rawPayload
) {
    public DiscordEventPayload withMaskedToken() {
        if (interactionToken == null || interactionToken.isBlank()) {
            return this;
        }
        return new DiscordEventPayload(
                eventType, correlationId, priority, guild, channelId, user,
                "[REDACTED]", messageId, version, attachments, rawPayload);
    }
}
