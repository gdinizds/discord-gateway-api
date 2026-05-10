package com.discord.gateway.publisher;

import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.router.TopicRegistry;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CircuitBreaker redpandaCb;
    private final TopicRegistry topicRegistry;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                          CircuitBreaker redpandaCircuitBreaker,
                          TopicRegistry topicRegistry,
                          ObjectMapper objectMapper,
                          MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.redpandaCb = redpandaCircuitBreaker;
        this.topicRegistry = topicRegistry;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public boolean publish(String topic, DiscordEventPayload payload, Runnable ephemeralFallback) {
        meterRegistry.counter("discord.gateway.events.received",
                "type", payload.eventType()).increment();
        try {
            String json = objectMapper.writeValueAsString(payload);
            redpandaCb.executeCallable(() -> {
                kafkaTemplate.send(topic, payload.guildId(), json).get(5, TimeUnit.SECONDS);
                return null;
            });
            meterRegistry.counter("discord.gateway.events.published",
                    "type", payload.eventType(), "topic", topic).increment();
            log.debug("Event published [type={}, topic={}, correlationId={}]",
                    payload.eventType(), topic, payload.correlationId());
            return true;
        } catch (Exception e) {
            log.error("Failed to publish event [type={}, topic={}]: {}",
                    payload.eventType(), topic, e.getMessage());
            meterRegistry.counter("discord.gateway.events.discarded",
                    "type", payload.eventType(), "reason", "publish_failure").increment();
            if (topicRegistry.isInteraction(payload.eventType()) && ephemeralFallback != null) {
                ephemeralFallback.run();
            } else {
                log.warn("Passive event discarded [type={}, guildId={}]",
                        payload.eventType(), payload.guildId());
            }
            return false;
        }
    }
}
