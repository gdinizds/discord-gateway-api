package com.discord.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BotCommandPayload(
        @JsonProperty("guild_id")   String guildId,
        String prefix,
        String name,
        String description,
        List<Map<String, Object>> parameters,
        @JsonProperty("is_deleted") boolean isDeleted,
        @JsonProperty("ephemeral")  Boolean ephemeral
) {
    public BotCommandPayload(String guildId, String prefix, String name, String description,
                             List<Map<String, Object>> parameters, boolean isDeleted) {
        this(guildId, prefix, name, description, parameters, isDeleted, null);
    }

    /** Whether the gateway should defer this slash command ephemerally. Absent means false. */
    public boolean isEphemeral() {
        return Boolean.TRUE.equals(ephemeral);
    }

    public boolean isValid() {
        return name != null && !name.isBlank()
                && prefix != null && !prefix.isBlank()
                && description != null && !description.isBlank();
    }

    public String resolvedGuildId() {
        return (guildId == null || guildId.isBlank()) ? "GLOBAL" : guildId;
    }
}
