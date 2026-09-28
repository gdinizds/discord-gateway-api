package com.discord.gateway.command;

import com.discord.gateway.domain.BotCommand;
import com.discord.gateway.domain.CommandEventType;
import com.discord.gateway.domain.CommandPrefix;
import com.discord.gateway.model.BotCommandPayload;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.restaction.CommandCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BotCommandConsumerTest {

    @Mock private BotCommandPersistenceService persistence;
    @Mock private JDA jda;
    @Mock private ObjectMapper objectMapper;

    private BotCommandConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BotCommandConsumer(persistence, jda, objectMapper);
    }

    @Test
    void shouldAcknowledgeAndDiscardInvalidPayload() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        BotCommandPayload payload = new BotCommandPayload(
                null, null, null, null, null, false
        );

        when(objectMapper.readValue("{}", BotCommandPayload.class)).thenReturn(payload);

        consumer.onCommand("{}", ack);

        verify(ack).acknowledge();
        verifyNoInteractions(persistence);
        verifyNoInteractions(jda);
    }

    @Test
    void shouldRegisterGlobalCommand() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        BotCommandPayload payload = new BotCommandPayload(
                null, "SLASH", "ping", "pong", List.of(), false
        );
        BotCommand entity = new BotCommand(null, CommandPrefix.SLASH, "ping", "pong", "[]");

        when(objectMapper.readValue("msg", BotCommandPayload.class)).thenReturn(payload);
        when(persistence.upsert(payload)).thenReturn(entity);

        @SuppressWarnings("unchecked")
        CommandCreateAction action = mock(CommandCreateAction.class);
        Command cmd = mock(Command.class);

        when(jda.upsertCommand(any(CommandData.class))).thenReturn(action);
        when(action.complete()).thenReturn(cmd);
        when(cmd.getId()).thenReturn("discord-cmd-1");

        consumer.onCommand("msg", ack);

        verify(ack).acknowledge();
        verify(jda).upsertCommand(any(CommandData.class));
        verify(persistence).saveResult(entity, CommandEventType.REGISTERED, "discord-cmd-1", true, null);
    }

    @Test
    void shouldDeleteGuildCommand() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        BotCommandPayload payload = new BotCommandPayload(
                "guild-1", "SLASH", "test", "test", List.of(), true
        );
        BotCommand entity = new BotCommand("guild-1", CommandPrefix.SLASH, "test", "test", "[]");
        entity.setDiscordCmdId("cmd-123");

        when(objectMapper.readValue("msg", BotCommandPayload.class)).thenReturn(payload);
        when(persistence.upsert(payload)).thenReturn(entity);

        Guild guild = mock(Guild.class);
        when(jda.getGuildById("guild-1")).thenReturn(guild);
        
        @SuppressWarnings("unchecked")
        net.dv8tion.jda.api.requests.restaction.AuditableRestAction<Void> deleteAction = mock(net.dv8tion.jda.api.requests.restaction.AuditableRestAction.class);
        when(guild.deleteCommandById("cmd-123")).thenReturn(deleteAction);

        consumer.onCommand("msg", ack);

        verify(ack).acknowledge();
        verify(deleteAction).complete();
        verify(persistence).saveResult(entity, CommandEventType.DELETED, null, true, null);
    }
}
