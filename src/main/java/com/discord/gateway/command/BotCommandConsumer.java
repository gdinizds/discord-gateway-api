package com.discord.gateway.command;

import com.discord.gateway.domain.BotCommand;
import com.discord.gateway.domain.CommandEventType;
import com.discord.gateway.model.BotCommandPayload;
import tools.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class BotCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(BotCommandConsumer.class);

    private final BotCommandPersistenceService persistence;
    private final JDA jda;
    private final ObjectMapper objectMapper;

    public BotCommandConsumer(BotCommandPersistenceService persistence,
                              JDA jda,
                              ObjectMapper objectMapper) {
        this.persistence = persistence;
        this.jda = jda;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "discord.gateway.commands", groupId = "discord-gateway")
    public void onCommand(String message, Acknowledgment acknowledgment) {
        try {
            BotCommandPayload payload = parse(message);
            if (payload == null) return;
            if (!payload.isValid()) {
                log.warn("Invalid bot command payload — missing required fields, discarding");
                return;
            }

            BotCommand command = persistence.upsert(payload);

            if (!"SLASH".equals(payload.prefix())) {
                return;
            }

            syncWithDiscord(command, payload);

        } catch (Exception e) {
            log.error("Unexpected error processing discord.gateway.commands", e);
        } finally {
            acknowledgment.acknowledge();
        }
    }

    private void syncWithDiscord(BotCommand command, BotCommandPayload payload) {
        boolean success = false;
        String discordCmdId = null;
        String discordError = null;
        CommandEventType eventType = CommandEventType.REGISTERED;

        try {
            if (payload.isDeleted()) {
                if (command.getDiscordCmdId() != null) {
                    deleteDiscordCommand(payload.resolvedGuildId(), command.getDiscordCmdId());
                    eventType = CommandEventType.DELETED;
                } else {
                    return;
                }
            } else {
                var discordCmd = registerDiscordCommand(payload);
                discordCmdId = discordCmd.getId();
                eventType = command.getVersion() > 1 ? CommandEventType.UPDATED : CommandEventType.REGISTERED;
            }
            success = true;
        } catch (Exception e) {
            discordError = e.getMessage();
            log.error("Discord API failed [command={}, guild={}]",
                    payload.name(), payload.resolvedGuildId(), e);
        }

        persistence.saveResult(command, eventType, discordCmdId, success, discordError);
    }

    private net.dv8tion.jda.api.interactions.commands.Command registerDiscordCommand(BotCommandPayload payload) {
        var slash = Commands.slash(payload.name(), payload.description());

        if (payload.parameters() != null) {
            for (var param : payload.parameters()) {
                String type = String.valueOf(param.getOrDefault("type", "STRING"));
                String pName = String.valueOf(param.get("name"));
                String pDesc = String.valueOf(param.getOrDefault("description", "-"));
                boolean required = Boolean.parseBoolean(String.valueOf(param.getOrDefault("required", "false")));
                var opt = new OptionData(OptionType.valueOf(type), pName, pDesc, required);
                slash.addOptions(opt);
            }
        }

        String guildId = payload.resolvedGuildId();
        if ("GLOBAL".equals(guildId)) {
            return jda.upsertCommand(slash).complete();
        }
        var guild = jda.getGuildById(guildId);
        if (guild == null) throw new IllegalStateException("Guild " + guildId + " not found in JDA cache");
        return guild.upsertCommand(slash).complete();
    }

    private void deleteDiscordCommand(String guildId, String discordCmdId) {
        if ("GLOBAL".equals(guildId)) {
            jda.deleteCommandById(discordCmdId).complete();
        } else {
            var guild = jda.getGuildById(guildId);
            if (guild != null) guild.deleteCommandById(discordCmdId).complete();
        }
    }

    private BotCommandPayload parse(String message) {
        try {
            return objectMapper.readValue(message, BotCommandPayload.class);
        } catch (Exception e) {
            log.warn("Failed to parse bot command payload — discarding [error={}]", e.getMessage());
            return null;
        }
    }
}
