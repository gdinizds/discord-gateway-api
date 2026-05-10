package com.discord.gateway.integration;

import com.discord.gateway.audit.GuildLifecycleService;
import com.discord.gateway.audit.InboundEventLogService;
import com.discord.gateway.executor.InteractionHookRegistry;
import com.discord.gateway.listener.DiscordEventListener;
import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.publisher.EventPublisher;
import com.discord.gateway.relay.AttachmentRelayService;
import com.discord.gateway.repository.GuildConfigRepository;
import com.discord.gateway.router.EventRouter;
import com.discord.gateway.router.TopicRegistry;
import tools.jackson.databind.json.JsonMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CircuitBreakerIT {

    CircuitBreaker redpandaCb;
    EventPublisher eventPublisher;
    EventRouter eventRouter;
    DiscordEventListener listener;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        redpandaCb = CircuitBreaker.ofDefaults("redpanda-cb-test");

        var topicRegistry          = new TopicRegistry();
        var objectMapper           = JsonMapper.builder().build();
        var meterRegistry          = new SimpleMeterRegistry();
        var guildConfigRepository  = mock(GuildConfigRepository.class);
        var kafkaTemplate          = mock(KafkaTemplate.class);
        var inboundLog             = mock(InboundEventLogService.class);
        var attachmentRelay        = mock(AttachmentRelayService.class);
        var hookRegistry           = new InteractionHookRegistry();
        var guildLifecycle         = mock(GuildLifecycleService.class);

        when(guildConfigRepository.findByGuildIdAndParam(anyString(), any())).thenReturn(Optional.empty());

        eventPublisher = new EventPublisher(kafkaTemplate, redpandaCb, topicRegistry, objectMapper, meterRegistry);
        eventRouter    = new EventRouter(topicRegistry, eventPublisher, guildConfigRepository, meterRegistry);
        listener       = new DiscordEventListener(eventRouter, inboundLog, attachmentRelay,
                hookRegistry, guildLifecycle, topicRegistry, meterRegistry);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void cbTransitionsToOpenAfterConsecutiveFailures() throws Exception {
        var kafkaTemplate = mock(KafkaTemplate.class);
        var future = mock(java.util.concurrent.CompletableFuture.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);
        when(future.get(anyLong(), any())).thenThrow(new RuntimeException("Kafka down"));

        var meterRegistry         = new SimpleMeterRegistry();
        var topicRegistry         = new TopicRegistry();
        var objectMapper          = JsonMapper.builder().build();
        var guildConfigRepository = mock(GuildConfigRepository.class);
        when(guildConfigRepository.findByGuildIdAndParam(anyString(), any())).thenReturn(Optional.empty());

        var cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .build();
        var testCb    = CircuitBreaker.of("cb-transition-test", cbConfig);
        var publisher = new EventPublisher(kafkaTemplate, testCb, topicRegistry, objectMapper, meterRegistry);
        var router    = new EventRouter(topicRegistry, publisher, guildConfigRepository, meterRegistry);

        var payload = new DiscordEventPayload("MESSAGE_CREATED", "corr-1", "normal",
                "guild-1", "channel-1", "user-1", null, "msg-1", 1, List.of(), Map.of());

        for (int i = 0; i < 10; i++) {
            router.route(payload, null);
        }

        assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void cbOpenSendsEphemeralForInteraction() {
        redpandaCb.transitionToOpenState();

        var guild   = mock(Guild.class);
        var user    = mock(User.class);
        var channel = mock(MessageChannelUnion.class);
        var event   = mock(SlashCommandInteractionEvent.class);
        var reply   = mock(ReplyCallbackAction.class);

        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("guild-1");
        when(event.getChannel()).thenReturn(channel);
        when(channel.getId()).thenReturn("channel-1");
        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("user-1");
        when(event.getToken()).thenReturn("token-1");
        when(event.getFullCommandName()).thenReturn("ping");
        when(event.getOptions()).thenReturn(List.of());
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        listener.onSlashCommandInteraction(event);

        verify(event).reply(argThat((String msg) -> msg.contains("indisponível")));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
    }

    @Test
    void cbOpenDiscardsPassiveEventSilently() {
        redpandaCb.transitionToOpenState();

        var payload = new DiscordEventPayload("MESSAGE_CREATED", "corr-2", "normal",
                "guild-1", "channel-1", "user-1", null, "msg-1", 1, List.of(), Map.of());

        boolean result = eventRouter.route(payload, null);
        assertThat(result).isFalse();
    }
}
