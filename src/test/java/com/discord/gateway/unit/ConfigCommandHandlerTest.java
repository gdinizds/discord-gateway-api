package com.discord.gateway.unit;

import com.discord.gateway.command.EphemeralCommandRegistry;
import com.discord.gateway.repository.BotCommandRepository;
import com.discord.gateway.audit.GuildConfigService;
import com.discord.gateway.audit.GuildLifecycleService;
import com.discord.gateway.audit.InboundEventLogService;
import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.executor.InteractionHookRegistry;
import com.discord.gateway.listener.DiscordEventListener;
import com.discord.gateway.listener.EventSequencer;
import com.discord.gateway.publisher.EventPublisher;
import com.discord.gateway.relay.AttachmentRelayService;
import com.discord.gateway.repository.GuildConfigRepository;
import com.discord.gateway.router.EventRouter;
import com.discord.gateway.router.TopicRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigCommandHandlerTest {

    @Mock SlashCommandInteractionEvent event;
    @Mock Guild guild;
    @Mock Member member;
    @Mock ReplyCallbackAction replyAction;
    @Mock GuildConfigService guildConfigService;
    @Mock com.discord.gateway.audit.SafeSearchConfigService safeSearchConfigService;
    @Mock GuildConfigRepository guildConfigRepository;
    @Mock EventPublisher eventPublisher;
    @Mock InboundEventLogService inboundLog;
    @Mock AttachmentRelayService attachmentRelay;
    @Mock GuildLifecycleService guildLifecycle;
    @Mock net.dv8tion.jda.api.entities.User user;
    @Mock net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion channel;

    DiscordEventListener listener;

    @BeforeEach
    void setUp() {
        var topicRegistry = new TopicRegistry();
        var meterRegistry = new SimpleMeterRegistry();
        var eventRouter   = new EventRouter(topicRegistry, eventPublisher, guildConfigRepository, meterRegistry);
        listener = new DiscordEventListener(eventRouter, inboundLog, attachmentRelay,
                new InteractionHookRegistry(), guildLifecycle, guildConfigService,
                safeSearchConfigService,
                topicRegistry, meterRegistry,
                new EphemeralCommandRegistry(mock(BotCommandRepository.class)),
                EventSequencer.direct());

        lenient().when(event.getName()).thenReturn("config");
        lenient().when(event.getGuild()).thenReturn(guild);
        lenient().when(guild.getId()).thenReturn("guild-1");
        lenient().when(event.getMember()).thenReturn(member);
        lenient().when(event.getUser()).thenReturn(user);
        lenient().when(user.getId()).thenReturn("user-1");
        lenient().when(event.getChannel()).thenReturn(channel);
        lenient().when(channel.getId()).thenReturn("chan-1");
        lenient().when(event.reply(anyString())).thenReturn(replyAction);
        lenient().when(replyAction.setEphemeral(true)).thenReturn(replyAction);
        lenient().when(event.getOption(anyString())).thenReturn(null);
        lenient().when(guildConfigRepository.findByGuildIdAndParam(anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void noPermission_respondsEphemeralAndSkipsService() {
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(false);

        listener.onSlashCommandInteraction(event);

        verify(event).reply(argThat((String msg) -> msg.contains("permissão") || msg.contains("Acesso negado")));
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
        verifyNoInteractions(guildConfigService);
        verifyNoInteractions(safeSearchConfigService);
    }

    @Test
    void validParam_delegatesToServiceAndRespondsEphemeral() {
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);

        var paramOpt = mock(OptionMapping.class);
        var valueOpt = mock(OptionMapping.class);
        when(paramOpt.getAsString()).thenReturn("ALLOWED_CHANNELS");
        when(valueOpt.getAsString()).thenReturn("*");
        when(event.getOption("parameter")).thenReturn(paramOpt);
        when(event.getOption("value")).thenReturn(valueOpt);
        when(guildConfigService.upsert("guild-1", GuildParam.ALLOWED_CHANNELS, "*"))
                .thenReturn(new GuildConfigService.ConfigResult(true, "Parâmetro atualizado."));

        listener.onSlashCommandInteraction(event);

        verify(guildConfigService).upsert("guild-1", GuildParam.ALLOWED_CHANNELS, "*");
        verify(event).reply("Parâmetro atualizado.");
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }

    @Test
    void serviceReturnsError_respondsWithErrorMessage() {
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);

        var paramOpt = mock(OptionMapping.class);
        var valueOpt = mock(OptionMapping.class);
        when(paramOpt.getAsString()).thenReturn("MAX_ATTACHMENT_SIZE_BYTES");
        when(valueOpt.getAsString()).thenReturn("bad");
        when(event.getOption("parameter")).thenReturn(paramOpt);
        when(event.getOption("value")).thenReturn(valueOpt);
        when(guildConfigService.upsert("guild-1", GuildParam.MAX_ATTACHMENT_SIZE_BYTES, "bad"))
                .thenReturn(new GuildConfigService.ConfigResult(false, "Valor inválido."));

        listener.onSlashCommandInteraction(event);

        verify(event).reply("Valor inválido.");
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }

    @Test
    void safeSearchGuildOption_delegatesToSafeSearchService() {
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);

        var safeSearchGuildOpt = mock(OptionMapping.class);
        when(safeSearchGuildOpt.getAsInt()).thenReturn(1);
        when(event.getOption("safesearch_guild")).thenReturn(safeSearchGuildOpt);
        when(safeSearchConfigService.setGuildSafeSearch("guild-1", "user-1", 1))
                .thenReturn(new com.discord.gateway.audit.SafeSearchConfigService.SafeSearchResult(true, "SafeSearch padrão da guilda configurado para: `1: Moderate (Filtro moderado)`."));

        listener.onSlashCommandInteraction(event);

        verify(safeSearchConfigService).setGuildSafeSearch("guild-1", "user-1", 1);
        verify(event).reply(contains("Moderate"));
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }

    @Test
    void safeSearchChannelOption_delegatesToSafeSearchService() {
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);

        var safeSearchChanOpt = mock(OptionMapping.class);
        when(safeSearchChanOpt.getAsInt()).thenReturn(0);
        when(event.getOption("safesearch_channel")).thenReturn(safeSearchChanOpt);
        when(safeSearchConfigService.setChannelSafeSearch("guild-1", "chan-1", "user-1", 0))
                .thenReturn(new com.discord.gateway.audit.SafeSearchConfigService.SafeSearchResult(true, "SafeSearch para o canal <#chan-1> configurado para: `0: Off (Sem filtro)`."));

        listener.onSlashCommandInteraction(event);

        verify(safeSearchConfigService).setChannelSafeSearch("guild-1", "chan-1", "user-1", 0);
        verify(event).reply(contains("Off"));
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }

    @Test
    void noOptionsProvided_repliesWithUsageMessage() {
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);

        listener.onSlashCommandInteraction(event);

        verify(event).reply(contains("Informe ao menos um parâmetro"));
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }
}
