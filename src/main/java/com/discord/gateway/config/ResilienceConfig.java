package com.discord.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class ResilienceConfig {

    static final CircuitBreakerConfig DEFAULTS = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(CircuitBreakerProperties properties, MeterRegistry meterRegistry) {
        var registry = CircuitBreakerRegistry.of(DEFAULTS);
        properties.instances().forEach((name, instance) -> registry.addConfiguration(name, toConfig(instance)));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public CircuitBreaker redpandaCircuitBreaker(CircuitBreakerRegistry registry) {
        return named(registry, "redpanda");
    }

    @Bean
    public CircuitBreaker postgresqlCircuitBreaker(CircuitBreakerRegistry registry) {
        return named(registry, "postgresql");
    }

    @Bean
    public CircuitBreaker garageCircuitBreaker(CircuitBreakerRegistry registry) {
        return named(registry, "garage");
    }

    private static CircuitBreaker named(CircuitBreakerRegistry registry, String name) {
        return registry.getConfiguration(name)
                .map(config -> registry.circuitBreaker(name, config))
                .orElseGet(() -> registry.circuitBreaker(name));
    }

    static CircuitBreakerConfig toConfig(CircuitBreakerProperties.Instance instance) {
        var builder = CircuitBreakerConfig.from(DEFAULTS);
        if (instance == null) return builder.build();
        if (instance.slidingWindowSize() != null) builder.slidingWindowSize(instance.slidingWindowSize());
        if (instance.minimumNumberOfCalls() != null) builder.minimumNumberOfCalls(instance.minimumNumberOfCalls());
        if (instance.failureRateThreshold() != null) builder.failureRateThreshold(instance.failureRateThreshold());
        if (instance.waitDurationInOpenState() != null) builder.waitDurationInOpenState(instance.waitDurationInOpenState());
        if (instance.permittedNumberOfCallsInHalfOpenState() != null) {
            builder.permittedNumberOfCallsInHalfOpenState(instance.permittedNumberOfCallsInHalfOpenState());
        }
        if (instance.slowCallDurationThreshold() != null) builder.slowCallDurationThreshold(instance.slowCallDurationThreshold());
        if (instance.slowCallRateThreshold() != null) builder.slowCallRateThreshold(instance.slowCallRateThreshold());
        return builder.build();
    }
}
