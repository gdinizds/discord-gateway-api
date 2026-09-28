package com.discord.gateway.dispatcher;

import com.discord.gateway.audit.MessageLogService;
import com.discord.gateway.executor.DiscordResponseExecutor;
import com.discord.gateway.model.DispatchResult;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.model.PayloadValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Component
public class ResponseDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ResponseDispatcher.class);

    public static final String RESPONSES_TOPIC = "discord.gateway.responses";
    public static final String DLT_TOPIC = "discord.gateway.responses.dlt";

    private final ResponseValidator validator;
    private final DiscordResponseExecutor executor;
    private final MessageLogService messageLogService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public ResponseDispatcher(ResponseValidator validator,
                              DiscordResponseExecutor executor,
                              MessageLogService messageLogService,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry,
                              KafkaTemplate<String, String> kafkaTemplate) {
        this.validator = validator;
        this.executor = executor;
        this.messageLogService = messageLogService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = RESPONSES_TOPIC, groupId = "discord-gateway")
    public void onResponse(String message,
                           @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                           Acknowledgment acknowledgment) {
        Timer.Sample sample = Timer.start(meterRegistry);
        OutboundResponsePayload payload = null;

        try {
            payload = objectMapper.readValue(message, OutboundResponsePayload.class);
            setupMdc(payload);

            validator.validate(payload, message);

            DispatchResult result = executor.execute(payload);
            messageLogService.logOutbound(payload, result, null, null);

            String resultTag = result.success() ? "success" : "discord_error";
            meterRegistry.counter("discord.gateway.dispatch.total",
                    "type", payload.responseType(), "result", resultTag).increment();
            sample.stop(meterRegistry.timer("discord.gateway.dispatch.latency",
                    "type", payload.responseType()));

            if (!result.success()) {
                log.error("Dispatch failed [responseType={}, error={}]",
                        payload.responseType(), result.discordError());
                deadLetter(key, message, "discord_error", result.discordError());
            }

        } catch (PayloadValidationException e) {
            log.error("Invalid payload rejected [reason={}, missingFields={}, payload={}]",
                    e.getReason(), e.getMissingFields(), e.getReceivedPayload());
            meterRegistry.counter("discord.gateway.validation.rejected",
                    "reason", e.getReason()).increment();

            if (payload != null) {
                messageLogService.logOutbound(payload, DispatchResult.failure("validation: " + e.getReason()),
                        null, null);
            }
            deadLetter(key, message, "validation", e.getReason() + " " + e.getMissingFields());

        } catch (Exception e) {
            log.error("Unexpected error processing payload [payload={}]", message, e);
            deadLetter(key, message, payload == null ? "unparseable" : "unexpected", e.getMessage());

        } finally {
            acknowledgment.acknowledge();
            MDC.clear();
        }
    }

    private void deadLetter(String key, String message, String reason, String detail) {
        try {
            var record = new ProducerRecord<>(DLT_TOPIC, key, message);
            record.headers().add(new RecordHeader("dlt-reason", bytes(reason)));
            if (detail != null) record.headers().add(new RecordHeader("dlt-detail", bytes(detail)));
            record.headers().add(new RecordHeader("dlt-source-topic", bytes(RESPONSES_TOPIC)));
            kafkaTemplate.send(record).whenComplete((r, e) -> {
                if (e != null) log.error("Failed to dead-letter response [reason={}]", reason, e);
            });
            meterRegistry.counter("discord.gateway.dispatch.dead_lettered", "reason", reason).increment();
        } catch (Exception e) {
            log.error("Failed to dead-letter response [reason={}]", reason, e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void setupMdc(OutboundResponsePayload payload) {
        if (payload.responseType() != null) MDC.put("response_type", payload.responseType());
        if (payload.correlationId() != null) MDC.put("correlation_id", payload.correlationId());
    }
}
