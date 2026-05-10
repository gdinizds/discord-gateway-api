package com.discord.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    private static final CircuitBreakerConfig BASE_CONFIG = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(BASE_CONFIG);
    }

    @Bean
    public CircuitBreaker redpandaCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("redpanda", BASE_CONFIG);
    }

    @Bean
    public CircuitBreaker postgresqlCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("postgresql", BASE_CONFIG);
    }

    @Bean
    public CircuitBreaker garageCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("garage", BASE_CONFIG);
    }
}
