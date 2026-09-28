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
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                new EphemeralCommandRegistry(mock(BotCommandRepository.class))
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
}