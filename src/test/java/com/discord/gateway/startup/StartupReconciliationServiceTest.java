package com.discord.gateway.startup;

import com.discord.gateway.audit.GuildLifecycleService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.CommandCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StartupReconciliationServiceTest {

    @Mock private GuildLifecycleService guildLifecycleService;
    
    private StartupReconciliationService startupReconciliationService;

    @BeforeEach
    void setUp() {
        startupReconciliationService = new StartupReconciliationService(guildLifecycleService, Runnable::run);
    }

    @Test
    void shouldReconcileGuildsAndRegisterCommandOnReady() {
        ReadyEvent readyEvent = mock(ReadyEvent.class);
        JDA jda = mock(JDA.class);
        Guild guild1 = mock(Guild.class);
        Guild guild2 = mock(Guild.class);

        when(readyEvent.getJDA()).thenReturn(jda);
        when(jda.getGuilds()).thenReturn(List.of(guild1, guild2));
        
        when(guild1.getId()).thenReturn("guild-1");
        when(guild1.getName()).thenReturn("Guild One");
        when(guild2.getId()).thenReturn("guild-2");
        when(guild2.getName()).thenReturn("Guild Two");

        @SuppressWarnings("unchecked")
        CommandCreateAction action = mock(CommandCreateAction.class);
        when(jda.upsertCommand(any(SlashCommandData.class))).thenReturn(action);

        startupReconciliationService.onReady(readyEvent);

        verify(guildLifecycleService).onJoin(guild1);
        verify(guildLifecycleService).onJoin(guild2);

        // Verify the command was created
        verify(jda).upsertCommand(any(SlashCommandData.class));
        verify(action).queue(any(), any());
    }

    @Test
    void shouldContinueReconciliationIfOneGuildFails() {
        ReadyEvent readyEvent = mock(ReadyEvent.class);
        JDA jda = mock(JDA.class);
        Guild guild1 = mock(Guild.class);
        Guild guild2 = mock(Guild.class);

        when(readyEvent.getJDA()).thenReturn(jda);
        when(jda.getGuilds()).thenReturn(List.of(guild1, guild2));
        
        when(guild1.getId()).thenReturn("guild-1");
        when(guild2.getId()).thenReturn("guild-2");

        doThrow(new RuntimeException("Failure")).when(guildLifecycleService).onJoin(guild1);

        @SuppressWarnings("unchecked")
        CommandCreateAction action = mock(CommandCreateAction.class);
        when(jda.upsertCommand(any(SlashCommandData.class))).thenReturn(action);

        startupReconciliationService.onReady(readyEvent);

        // Verify it still called onJoin for guild2 even though guild1 threw an exception
        verify(guildLifecycleService).onJoin(guild1);
        verify(guildLifecycleService).onJoin(guild2);
    }
}