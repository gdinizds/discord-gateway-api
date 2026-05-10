package com.discord.gateway.unit;

import com.discord.gateway.domain.GuildConfig;
import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.publisher.EventPublisher;
import com.discord.gateway.repository.GuildConfigRepository;
import com.discord.gateway.router.EventRouter;
import com.discord.gateway.router.TopicRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventRouterTest {

    @Mock EventPublisher eventPublisher;
    @Mock GuildConfigRepository guildConfigRepository;

    TopicRegistry topicRegistry;
    EventRouter eventRouter;

    @BeforeEach
    void setUp() {
        topicRegistry = new TopicRegistry();
        topicRegistry.setTopics(Map.of(
                "MESSAGE_CREATED",     new TopicRegistry.TopicMapping("discord.events.message.created",     "normal"),
                "MESSAGE_UPDATED",     new TopicRegistry.TopicMapping("discord.events.message.updated",     "low"),
                "INTERACTION_COMMAND", new TopicRegistry.TopicMapping("discord.events.interaction.command", "normal"),
                "INTERACTION_BUTTON",  new TopicRegistry.TopicMapping("discord.events.interaction.button",  "normal"),
                "INTERACTION_MODAL",   new TopicRegistry.TopicMapping("discord.events.interaction.modal",   "normal"),
                "GUILD_MEMBER",        new TopicRegistry.TopicMapping("discord.events.guild.member",        "low"),
                "GUILD_UPDATED",       new TopicRegistry.TopicMapping("discord.events.guild.updated",       "low")
        ));
        eventRouter = new EventRouter(topicRegistry, eventPublisher, guildConfigRepository, new SimpleMeterRegistry());
        lenient().when(guildConfigRepository.findByGuildIdAndParam(anyString(), any())).thenReturn(Optional.empty());
        lenient().when(eventPublisher.publish(anyString(), any(), any())).thenReturn(true);
    }

    @Test
    void messageCreatedRoutesToCorrectTopic() {
        eventRouter.route(payload("MESSAGE_CREATED"), null);
        verify(eventPublisher).publish(eq("discord.events.message.created"), any(), isNull());
    }

    @Test
    void messageUpdatedRoutesToCorrectTopic() {
        eventRouter.route(payload("MESSAGE_UPDATED"), null);
        verify(eventPublisher).publish(eq("discord.events.message.updated"), any(), isNull());
    }

    @Test
    void interactionCommandRoutesToCorrectTopic() {
        eventRouter.route(payload("INTERACTION_COMMAND"), null);
        verify(eventPublisher).publish(eq("discord.events.interaction.command"), any(), isNull());
    }

    @Test
    void interactionButtonRoutesToCorrectTopic() {
        eventRouter.route(payload("INTERACTION_BUTTON"), null);
        verify(eventPublisher).publish(eq("discord.events.interaction.button"), any(), isNull());
    }

    @Test
    void interactionModalRoutesToCorrectTopic() {
        eventRouter.route(payload("INTERACTION_MODAL"), null);
        verify(eventPublisher).publish(eq("discord.events.interaction.modal"), any(), isNull());
    }

    @Test
    void guildMemberRoutesToCorrectTopic() {
        eventRouter.route(payload("GUILD_MEMBER"), null);
        verify(eventPublisher).publish(eq("discord.events.guild.member"), any(), isNull());
    }

    @Test
    void guildUpdatedRoutesToCorrectTopic() {
        eventRouter.route(payload("GUILD_UPDATED"), null);
        verify(eventPublisher).publish(eq("discord.events.guild.updated"), any(), isNull());
    }

    @Test
    void unknownEventTypeIsDiscarded() {
        boolean result = eventRouter.route(payload("UNKNOWN_TYPE"), null);
        assertThat(result).isFalse();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void channelNotInAllowedListIsFiltered() {
        var config = new GuildConfig("guild-1", GuildParam.ALLOWED_CHANNELS, "channel-allowed");
        when(guildConfigRepository.findByGuildIdAndParam("guild-1", GuildParam.ALLOWED_CHANNELS))
                .thenReturn(Optional.of(config));

        boolean result = eventRouter.route(payload("MESSAGE_CREATED"), null);
        assertThat(result).isFalse();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void channelInAllowedListIsPassed() {
        var config = new GuildConfig("guild-1", GuildParam.ALLOWED_CHANNELS, "channel-1,channel-2");
        when(guildConfigRepository.findByGuildIdAndParam("guild-1", GuildParam.ALLOWED_CHANNELS))
                .thenReturn(Optional.of(config));

        boolean result = eventRouter.route(payload("MESSAGE_CREATED"), null);
        assertThat(result).isTrue();
        verify(eventPublisher).publish(anyString(), any(), any());
    }

    @Test
    void wildcardAllowedChannelsPassesAnyChannel() {
        var config = new GuildConfig("guild-1", GuildParam.ALLOWED_CHANNELS, "*");
        when(guildConfigRepository.findByGuildIdAndParam("guild-1", GuildParam.ALLOWED_CHANNELS))
                .thenReturn(Optional.of(config));

        boolean result = eventRouter.route(payload("MESSAGE_CREATED"), null);
        assertThat(result).isTrue();
    }

    @Test
    void noChannelConfigAllowsAll() {
        boolean result = eventRouter.route(payload("MESSAGE_CREATED"), null);
        assertThat(result).isTrue();
    }

    private DiscordEventPayload payload(String eventType) {
        return new DiscordEventPayload(eventType, "corr-1", "normal",
                "guild-1", "channel-1", "user-1",
                null, null, null, 1, List.of(), Map.of());
    }
}
