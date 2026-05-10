package com.discord.gateway.router;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TopicRegistry {

    public record TopicMapping(String topic, String priority) {}

    private static final Map<String, TopicMapping> MAPPINGS = Map.of(
            "MESSAGE_CREATED",     new TopicMapping("discord.events.message.created",    "normal"),
            "MESSAGE_UPDATED",     new TopicMapping("discord.events.message.updated",    "low"),
            "INTERACTION_COMMAND", new TopicMapping("discord.events.interaction.command", "normal"),
            "INTERACTION_BUTTON",  new TopicMapping("discord.events.interaction.button", "normal"),
            "INTERACTION_MODAL",   new TopicMapping("discord.events.interaction.modal",  "normal"),
            "GUILD_MEMBER",        new TopicMapping("discord.events.guild.member",       "low"),
            "GUILD_UPDATED",       new TopicMapping("discord.events.guild.updated",      "low")
    );

    public TopicMapping get(String eventType) {
        return MAPPINGS.get(eventType);
    }

    public boolean isInteraction(String eventType) {
        return eventType != null && eventType.startsWith("INTERACTION_");
    }
}
