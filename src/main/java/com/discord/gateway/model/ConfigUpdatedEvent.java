package com.discord.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigUpdatedEvent(
        String eventType,
        String correlationId,
        String guildId,
        String channelId,
        String userId,
        String settingKey,
        String oldValue,
        String newValue,
        Integer level,
        String timestamp
) {
}
