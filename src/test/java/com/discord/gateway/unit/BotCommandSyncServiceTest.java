package com.discord.gateway.unit;

import com.discord.gateway.command.BotCommandConsumer;
import com.discord.gateway.command.BotCommandPersistenceService;
import com.discord.gateway.domain.BotCommand;
import com.discord.gateway.domain.CommandEventType;
import com.discord.gateway.domain.CommandPrefix;
import com.discord.gateway.model.BotCommandPayload;
import tools.jackson.databind.json.JsonMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.restaction.CommandCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BotCommandSyncServiceTest {

    @Mock BotCommandPersistenceService persistence;
    @Mock JDA jda;
    @Mock Acknowledgment ack;

    BotCommandConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BotCommandConsumer(persistence, jda, JsonMapper.builder().build());
    }

    @Test
    void validSlashPayload_upsertsAndRegistersWithDiscord() throws Exception {
        var payload = new BotCommandPayload("bot-1", "guild-1", "SLASH", "ping", "Pinga", List.of(), false);
        var command = new BotCommand("guild-1", "bot-1", CommandPrefix.SLASH, "ping", "Pinga", "[]");
        when(persistence.upsert(any())).thenReturn(command);

        var guild = mock(Guild.class);
        var restAction = mock(net.dv8tion.jda.api.requests.restaction.CommandCreateAction.class);
        var discordCmd = mock(net.dv8tion.jda.api.interactions.commands.Command.class);
        when(jda.getGuildById("guild-1")).thenReturn(guild);
        when(guild.upsertCommand(any(net.dv8tion.jda.api.interactions.commands.build.CommandData.class))).thenReturn(restAction);
        when(restAction.complete()).thenReturn(discordCmd);
        when(discordCmd.getId()).thenReturn("discord-cmd-123");

        consumer.onCommand(toJson(payload), ack);

        verify(persistence).upsert(any());
        verify(persistence).saveResult(eq(command), eq(CommandEventType.REGISTERED),
                eq("discord-cmd-123"), eq(true), isNull());
        verify(ack).acknowledge();
    }

    @Test
    void invalidPayload_discarded_noInteractionWithDb() throws Exception {
        var invalid = new BotCommandPayload(null, "guild-1", "SLASH", "ping", "Pinga", List.of(), false);

        consumer.onCommand(toJson(invalid), ack);

        verifyNoInteractions(persistence);
        verify(ack).acknowledge();
    }

    @Test
    void dotPrefixPayload_upsertsButSkipsDiscord() throws Exception {
        var payload = new BotCommandPayload("bot-1", "guild-1", "DOT", "help", "Ajuda", List.of(), false);
        var command = new BotCommand("guild-1", "bot-1", CommandPrefix.DOT, "help", "Ajuda", "[]");
        when(persistence.upsert(any())).thenReturn(command);

        consumer.onCommand(toJson(payload), ack);

        verify(persistence).upsert(any());
        verifyNoInteractions(jda);
        verify(ack).acknowledge();
    }

    @Test
    void discordApiFailure_logsFailureAndAcknowledges() throws Exception {
        var payload = new BotCommandPayload("bot-1", "guild-1", "SLASH", "ping", "Pinga", List.of(), false);
        var command = new BotCommand("guild-1", "bot-1", CommandPrefix.SLASH, "ping", "Pinga", "[]");
        when(persistence.upsert(any())).thenReturn(command);
        when(jda.getGuildById("guild-1")).thenThrow(new RuntimeException("Discord down"));

        consumer.onCommand(toJson(payload), ack);

        verify(persistence).saveResult(eq(command), eq(CommandEventType.REGISTERED),
                isNull(), eq(false), contains("Discord down"));
        verify(ack).acknowledge();
    }

    @Test
    void isDeletedTrue_callsDeleteOnDiscordAndLogs() throws Exception {
        var payload = new BotCommandPayload("bot-1", "guild-1", "SLASH", "ping", "Pinga", List.of(), true);
        var command = new BotCommand("guild-1", "bot-1", CommandPrefix.SLASH, "ping", "Pinga", "[]");
        command.setDiscordCmdId("discord-cmd-123");
        when(persistence.upsert(any())).thenReturn(command);

        var guild = mock(Guild.class);
        var deleteAction = mock(net.dv8tion.jda.api.requests.RestAction.class);
        when(jda.getGuildById("guild-1")).thenReturn(guild);
        when(guild.deleteCommandById("discord-cmd-123")).thenReturn(deleteAction);
        when(deleteAction.complete()).thenReturn(null);

        consumer.onCommand(toJson(payload), ack);

        verify(persistence).saveResult(eq(command), eq(CommandEventType.DELETED),
                isNull(), eq(true), isNull());
        verify(ack).acknowledge();
    }

    private String toJson(BotCommandPayload p) {
        try {
            return JsonMapper.builder().build().writeValueAsString(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
