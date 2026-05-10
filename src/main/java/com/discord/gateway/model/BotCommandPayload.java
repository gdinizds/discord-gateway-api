package com.discord.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BotCommandPayload(
        @JsonProperty("bot_id")     String botId,
        @JsonProperty("guild_id")   String guildId,
        String prefix,
        String name,
        String description,
        List<Map<String, Object>> parameters,
        @JsonProperty("is_deleted") boolean isDeleted
) {
    public boolean isValid() {
        return botId != null && !botId.isBlank()
                && name != null && !name.isBlank()
                && prefix != null && !prefix.isBlank()
                && description != null && !description.isBlank();
    }

    public String resolvedGuildId() {
        return (guildId == null || guildId.isBlank()) ? "GLOBAL" : guildId;
    }
}
