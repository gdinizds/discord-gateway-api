package com.discord.gateway.dispatcher;

import com.discord.gateway.audit.MessageLogService;
import com.discord.gateway.executor.DiscordResponseExecutor;
import com.discord.gateway.model.DispatchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResponseDispatcherTest {

    @Mock DiscordResponseExecutor executor;
    @Mock MessageLogService messageLogService;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock Acknowledgment ack;

    SimpleMeterRegistry meterRegistry;
    ResponseDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dispatcher = new ResponseDispatcher(new ResponseValidator(), executor, messageLogService,
                JsonMapper.builder().build(), meterRegistry, kafkaTemplate);
    }

    @Test
    void successfulDispatchIsNotDeadLettered() {
        when(executor.execute(any())).thenReturn(DispatchResult.success("m-1", "c-1"));

        dispatcher.onResponse(reply(), "guild-1", ack);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(ack).acknowledge();
    }

    @Test
    void discordFailureIsDeadLetteredWithReason() {
        when(executor.execute(any())).thenReturn(DispatchResult.failure("Unknown Channel"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        dispatcher.onResponse(reply(), "guild-1", ack);

        var record = captureDeadLetter();
        assertThat(record.topic()).isEqualTo(ResponseDispatcher.DLT_TOPIC);
        assertThat(record.key()).isEqualTo("guild-1");
        assertThat(record.value()).isEqualTo(reply());
        assertThat(header(record, "dlt-reason")).isEqualTo("discord_error");
        assertThat(header(record, "dlt-detail")).isEqualTo("Unknown Channel");
        verify(ack).acknowledge();
    }

    @Test
    void invalidPayloadIsDeadLetteredAsValidation() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        dispatcher.onResponse("{\"responseType\":\"NOPE\",\"interactionToken\":\"t\"}", null, ack);

        assertThat(header(captureDeadLetter(), "dlt-reason")).isEqualTo("validation");
        verify(executor, never()).execute(any());
        verify(ack).acknowledge();
    }

    @Test
    void unparseablePayloadIsDeadLettered() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        dispatcher.onResponse("not json", null, ack);

        assertThat(header(captureDeadLetter(), "dlt-reason")).isEqualTo("unparseable");
        verify(ack).acknowledge();
    }

    @Test
    void deadLetterFailureStillAcknowledges() {
        when(executor.execute(any())).thenReturn(DispatchResult.failure("boom"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("broker down"));

        dispatcher.onResponse(reply(), "guild-1", ack);

        verify(ack).acknowledge();
    }

    private static String reply() {
        return "{\"responseType\":\"REPLY\",\"interactionToken\":\"tok\",\"content\":\"oi\"}";
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captureDeadLetter() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
