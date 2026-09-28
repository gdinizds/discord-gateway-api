package com.discord.gateway.listener;

import com.discord.gateway.command.EphemeralCommandRegistry;
import com.discord.gateway.repository.BotCommandRepository;
import com.discord.gateway.audit.GuildConfigService;
import com.discord.gateway.audit.GuildLifecycleService;
import com.discord.gateway.audit.InboundEventLogService;
import com.discord.gateway.executor.InteractionHookRegistry;
import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.relay.AttachmentRelayService;
import com.discord.gateway.router.EventRouter;
import com.discord.gateway.router.TopicRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DiscordEventListenerTest {

    @Mock private EventRouter eventRouter;
    @Mock private InboundEventLogService inboundEventLogService;
    @Mock private AttachmentRelayService attachmentRelayService;
    @Mock private InteractionHookRegistry hookRegistry;
    @Mock private GuildLifecycleService guildLifecycleService;
    @Mock private GuildConfigService guildConfigService;
    @Mock private TopicRegistry topicRegistry;
    
    private MeterRegistry meterRegistry;
    private DiscordEventListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = new DiscordEventListener(
                eventRouter, inboundEventLogService, attachmentRelayService, 
                hookRegistry, guildLifecycleService, guildConfigService, 
                topicRegistry, meterRegistry,
                new EphemeralCommandRegistry(mock(BotCommandRepository.class)),
                EventSequencer.direct()
        );
    }

    @Test
    void shouldIgnoreMessageFromBot() {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        User author = mock(User.class);
        
        when(event.isFromGuild()).thenReturn(true);
        when(event.getAuthor()).thenReturn(author);
        when(author.isBot()).thenReturn(true);

        listener.onMessageReceived(event);

        verifyNoInteractions(eventRouter);
    }

    @Test
    void shouldIgnoreMessageNotFromGuild() {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        when(event.isFromGuild()).thenReturn(false);

        listener.onMessageReceived(event);

        verifyNoInteractions(eventRouter);
    }

    @Test
    void shouldRouteStandardMessage() {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        User author = mock(User.class);
        Guild guild = mock(Guild.class);
        Message message = mock(Message.class);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);

        when(event.isFromGuild()).thenReturn(true);
        when(event.getAuthor()).thenReturn(author);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMessage()).thenReturn(message);
        when(event.getChannel()).thenReturn(channel);

        when(author.isBot()).thenReturn(false);
        when(message.getContentRaw()).thenReturn("Hello world");
        when(message.getAttachments()).thenReturn(List.of());
        when(guild.getId()).thenReturn("guild-1");
        when(guild.getName()).thenReturn("Guild 1");
        when(channel.getId()).thenReturn("channel-1");
        when(author.getId()).thenReturn("user-1");
        when(message.getId()).thenReturn("msg-1");
        
        // Mock topic config, creating actual record
        TopicRegistry.TopicMapping topicMapping = new TopicRegistry.TopicMapping("test-topic", "normal");
        when(topicRegistry.get("MESSAGE_CREATED")).thenReturn(topicMapping);

        listener.onMessageReceived(event);

        ArgumentCaptor<DiscordEventPayload> captor = ArgumentCaptor.forClass(DiscordEventPayload.class);
        verify(eventRouter).route(captor.capture(), eq(null));
        verify(inboundEventLogService).log(captor.getValue());

        DiscordEventPayload payload = captor.getValue();
        assertThat(payload.eventType()).isEqualTo("MESSAGE_CREATED");
        assertThat(payload.guild().getId()).isEqualTo("guild-1");
        assertThat(payload.channelId()).isEqualTo("channel-1");
        assertThat(payload.user().getId()).isEqualTo("user-1");
        assertThat(payload.messageId()).isEqualTo("msg-1");
        
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> raw = (java.util.Map<String, Object>) payload.rawPayload();
        assertThat(raw).containsEntry("content", "Hello world");
    }

    @Test
    void slashCommandIsDeferredAndHookRegisteredBeforePublishing() {
        var event = slashEvent();
        var hook = mock(InteractionHook.class);
        var deferAction = mock(ReplyCallbackAction.class);
        when(event.getHook()).thenReturn(hook);
        when(event.deferReply(false)).thenReturn(deferAction);
        when(eventRouter.isChannelAllowed("guild-1", "channel-1")).thenReturn(true);
        when(eventRouter.route(any(), any(), any())).thenReturn(true);

        listener.onSlashCommandInteraction(event);

        var order = inOrder(hookRegistry, event, eventRouter);
        order.verify(hookRegistry).register("token-1", hook);
        order.verify(event).deferReply(false);
        order.verify(eventRouter).route(any(), any(), eq(Map.of("command-name", "ping")));
        verify(deferAction).queue(isNull(), any());
        verify(inboundEventLogService).log(any());
    }

    @Test
    void slashCommandInFilteredChannelRepliesEphemeralWithoutPublishing() {
        var event = slashEvent();
        var replyAction = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);
        when(eventRouter.isChannelAllowed("guild-1", "channel-1")).thenReturn(false);

        listener.onSlashCommandInteraction(event);

        verify(event).reply(DiscordEventListener.CHANNEL_NOT_ALLOWED_MESSAGE);
        verify(replyAction).queue(isNull(), any());
        verify(event, never()).deferReply(anyBoolean());
        verify(eventRouter, never()).route(any(), any(), any());
        verifyNoInteractions(hookRegistry, inboundEventLogService);
        assertThat(meterRegistry.counter("discord.gateway.events.discarded",
                "type", "INTERACTION_COMMAND", "reason", "channel_filtered").count()).isEqualTo(1.0);
    }

    @Test
    void memberJoinPublishesEventWithoutTouchingGuildRegistry() {
        var event = mock(GuildMemberJoinEvent.class);
        var guild = mock(Guild.class);
        var user = mock(User.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getUser()).thenReturn(user);
        when(guild.getId()).thenReturn("guild-1");

        listener.onGuildMemberJoin(event);

        var captor = ArgumentCaptor.forClass(DiscordEventPayload.class);
        verify(eventRouter).route(captor.capture(), isNull());
        assertThat(captor.getValue().eventType()).isEqualTo("GUILD_MEMBER");
        assertThat(captor.getValue().rawPayload()).containsEntry("action", "JOIN");
        verifyNoInteractions(guildLifecycleService);
    }

    @Test
    void memberRemovePublishesEventWithoutMarkingGuildAsLeft() {
        var event = mock(GuildMemberRemoveEvent.class);
        var guild = mock(Guild.class);
        var user = mock(User.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getUser()).thenReturn(user);
        when(guild.getId()).thenReturn("guild-1");

        listener.onGuildMemberRemove(event);

        var captor = ArgumentCaptor.forClass(DiscordEventPayload.class);
        verify(eventRouter).route(captor.capture(), isNull());
        assertThat(captor.getValue().rawPayload()).containsEntry("action", "LEAVE");
        verifyNoInteractions(guildLifecycleService);
    }

    @Test
    void botJoiningGuildRegistersIt() {
        var event = mock(GuildJoinEvent.class);
        var guild = mock(Guild.class);
        when(event.getGuild()).thenReturn(guild);

        listener.onGuildJoin(event);

        verify(guildLifecycleService).onJoin(guild);
    }

    @Test
    void botLeavingGuildMarksItAsLeft() {
        var event = mock(GuildLeaveEvent.class);
        var guild = mock(Guild.class);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("guild-1");
        when(guild.getName()).thenReturn("Guild 1");

        listener.onGuildLeave(event);

        verify(guildLifecycleService).onLeave("guild-1", "Guild 1");
    }

    private SlashCommandInteractionEvent slashEvent() {
        var event = mock(SlashCommandInteractionEvent.class);
        var guild = mock(Guild.class);
        var user = mock(User.class);
        var channel = mock(MessageChannelUnion.class);
        lenient().when(event.getGuild()).thenReturn(guild);
        lenient().when(guild.getId()).thenReturn("guild-1");
        lenient().when(event.getName()).thenReturn("ping");
        lenient().when(event.getFullCommandName()).thenReturn("ping");
        lenient().when(event.getChannel()).thenReturn(channel);
        lenient().when(channel.getId()).thenReturn("channel-1");
        lenient().when(event.getUser()).thenReturn(user);
        lenient().when(user.getId()).thenReturn("user-1");
        lenient().when(event.getToken()).thenReturn("token-1");
        lenient().when(event.getOptions()).thenReturn(List.of());
        return event;
    }
}
