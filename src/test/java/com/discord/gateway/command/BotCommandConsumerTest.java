package com.discord.gateway.command;

import com.discord.gateway.domain.BotCommand;
import com.discord.gateway.domain.CommandEventType;
import com.discord.gateway.model.BotCommandPayload;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.restaction.CommandCreateAction;
import net.dv8tion.jda.api.requests.restaction.CommandEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
                null, null, null, null, null, null, false, List.of()
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
                "app-1", "GLOBAL", "ping", "pong", "SLASH", "1", false, List.of()
        );
        BotCommand entity = new BotCommand();
        entity.setVersion(1);

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
                "app-2", "guild-1", "test", "test", "SLASH", "1", true, List.of()
        );
        BotCommand entity = new BotCommand();
        entity.setVersion(1);
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