package com.discord.gateway.unit;

import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.publisher.EventPublisher;
import com.discord.gateway.router.EventRouter;
import com.discord.gateway.router.TopicRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventRouterTest {

    @Mock EventPublisher eventPublisher;
    @Mock NamedParameterJdbcTemplate jdbc;

    TopicRegistry topicRegistry;
    EventRouter eventRouter;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        topicRegistry = new TopicRegistry();
        eventRouter   = new EventRouter(topicRegistry, eventPublisher, jdbc, new SimpleMeterRegistry());
        doReturn(List.of()).when(jdbc).query(anyString(), any(Map.class), any(RowMapper.class));
        when(eventPublisher.publish(anyString(), any(), any())).thenReturn(true);
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    void channelNotInAllowedListIsFiltered() {
        doReturn(List.of("channel-allowed")).when(jdbc).query(anyString(), any(Map.class), any(RowMapper.class));

        boolean result = eventRouter.route(payload("MESSAGE_CREATED"), null);
        assertThat(result).isFalse();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void channelInAllowedListIsPassed() {
        doReturn(List.of("channel-1,channel-2")).when(jdbc).query(anyString(), any(Map.class), any(RowMapper.class));

        boolean result = eventRouter.route(payload("MESSAGE_CREATED"), null);
        assertThat(result).isTrue();
        verify(eventPublisher).publish(anyString(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wildcardAllowedChannelsPassesAnyChannel() {
        doReturn(List.of("*")).when(jdbc).query(anyString(), any(Map.class), any(RowMapper.class));

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
                null, null, 1, List.of(), Map.of());
    }
}
