package com.discord.gateway.dispatcher;

import com.discord.gateway.audit.MessageLogService;
import com.discord.gateway.executor.DiscordResponseExecutor;
import com.discord.gateway.model.DispatchResult;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.model.PayloadValidationException;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class ResponseDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ResponseDispatcher.class);

    private final ResponseValidator validator;
    private final DiscordResponseExecutor executor;
    private final MessageLogService messageLogService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ResponseDispatcher(ResponseValidator validator,
                              DiscordResponseExecutor executor,
                              MessageLogService messageLogService,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.validator = validator;
        this.executor = executor;
        this.messageLogService = messageLogService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "discord.gateway.responses", groupId = "discord-gateway")
    public void onResponse(String message, Acknowledgment acknowledgment) {
        Timer.Sample sample = Timer.start(meterRegistry);
        OutboundResponsePayload payload = null;
        String rawJson = message;

        try {
            payload = objectMapper.readValue(message, OutboundResponsePayload.class);
            setupMdc(payload);

            validator.validate(payload, rawJson);

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

        } catch (Exception e) {
            log.error("Unexpected error processing payload [payload={}]", rawJson, e);

        } finally {
            acknowledgment.acknowledge();
            MDC.clear();
        }
    }

    private void setupMdc(OutboundResponsePayload payload) {
        if (payload.responseType() != null) MDC.put("response_type", payload.responseType());
        if (payload.correlationId() != null) MDC.put("correlation_id", payload.correlationId());
    }
}
