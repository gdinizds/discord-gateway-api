package com.discord.gateway.publisher;

import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.model.GuildInfo;
import com.discord.gateway.router.TopicRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class EventPublisherTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private TopicRegistry topicRegistry;
    @Mock private ObjectMapper objectMapper;

    private MeterRegistry meterRegistry;
    private EventPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        CircuitBreaker cb = CircuitBreaker.ofDefaults("test");
        publisher = new EventPublisher(kafkaTemplate, cb, topicRegistry, objectMapper, meterRegistry);
    }

    @Test
    void publishShouldSendToKafkaWithoutHeaders() throws Exception {
        DiscordEventPayload payload = createPayload("guild-1");
        when(objectMapper.writeValueAsString(payload)).thenReturn("{\"json\":true}");

        @SuppressWarnings("unchecked")
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future = mock(CompletableFuture.class);
        when(kafkaTemplate.send(eq("test-topic"), eq("guild-1"), eq("{\"json\":true}"))).thenReturn(future);
        when(future.get(anyLong(), any())).thenReturn(null);

        boolean success = publisher.publish("test-topic", payload, null);

        assertThat(success).isTrue();
        verify(kafkaTemplate).send("test-topic", "guild-1", "{\"json\":true}");
    }

    @Test
    void publishShouldSendToKafkaWithHeaders() throws Exception {
        DiscordEventPayload payload = createPayload("guild-2");
        when(objectMapper.writeValueAsString(payload)).thenReturn("{\"json\":true}");

        @SuppressWarnings("unchecked")
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future = mock(CompletableFuture.class);
        
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
        when(future.get(anyLong(), any())).thenReturn(null);

        Map<String, String> headers = Map.of("command-name", "ping");

        boolean success = publisher.publish("test-topic", payload, null, headers);

        assertThat(success).isTrue();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("test-topic");
        assertThat(record.key()).isEqualTo("guild-2");
        assertThat(record.value()).isEqualTo("{\"json\":true}");
        assertThat(record.headers().lastHeader("command-name").value()).isEqualTo("ping".getBytes());
    }

    @Test
    void publishShouldExecuteFallbackOnFailure() throws Exception {
        DiscordEventPayload payload = createPayload("guild-1");
        when(objectMapper.writeValueAsString(payload)).thenThrow(new RuntimeException("Serialization failure"));
        when(topicRegistry.isInteraction(payload.eventType())).thenReturn(true);

        Runnable fallback = mock(Runnable.class);

        boolean success = publisher.publish("test-topic", payload, fallback);

        assertThat(success).isFalse();
        verify(fallback).run();
        verifyNoInteractions(kafkaTemplate);
    }

    private DiscordEventPayload createPayload(String guildId) {
        GuildInfo guild = GuildInfo.builder()
                .id(guildId)
                .name("Guild")
                .build();
        return new DiscordEventPayload(
                "TEST_EVENT", "cor-1", "normal",
                guild, "channel-1", null, null, null, 1, List.of(), Map.of());
    }
}