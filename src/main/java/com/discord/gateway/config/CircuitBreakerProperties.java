package com.discord.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "gateway.circuit-breakers")
public record CircuitBreakerProperties(Map<String, Instance> instances) {

    public CircuitBreakerProperties {
        instances = instances == null ? Map.of() : Map.copyOf(instances);
    }

    public record Instance(
            Integer slidingWindowSize,
            Integer minimumNumberOfCalls,
            Float failureRateThreshold,
            Duration waitDurationInOpenState,
            Integer permittedNumberOfCallsInHalfOpenState,
            Duration slowCallDurationThreshold,
            Float slowCallRateThreshold
    ) {}
}
