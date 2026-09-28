package com.discord.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ResilienceConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void yamlSettingsAreAppliedPerInstance() {
        contextRunner
                .withPropertyValues(
                        "gateway.circuit-breakers.instances.redpanda.sliding-window-size=50",
                        "gateway.circuit-breakers.instances.redpanda.minimum-number-of-calls=20",
                        "gateway.circuit-breakers.instances.garage.failure-rate-threshold=25")
                .run(context -> {
                    var redpanda = context.getBean("redpandaCircuitBreaker", CircuitBreaker.class).getCircuitBreakerConfig();
                    assertThat(redpanda.getSlidingWindowSize()).isEqualTo(50);
                    assertThat(redpanda.getMinimumNumberOfCalls()).isEqualTo(20);

                    var garage = context.getBean("garageCircuitBreaker", CircuitBreaker.class).getCircuitBreakerConfig();
                    assertThat(garage.getFailureRateThreshold()).isEqualTo(25f);
                    assertThat(garage.getSlidingWindowSize()).isEqualTo(10);
                });
    }

    @Test
    void instancesWithoutSettingsUseDefaults() {
        contextRunner.run(context -> {
            var postgres = context.getBean("postgresqlCircuitBreaker", CircuitBreaker.class).getCircuitBreakerConfig();
            assertThat(postgres.getSlidingWindowSize()).isEqualTo(ResilienceConfig.DEFAULTS.getSlidingWindowSize());
            assertThat(context.getBean(CircuitBreakerRegistry.class).getAllCircuitBreakers()).hasSize(3);
        });
    }

    @Test
    void circuitBreakerStateIsExportedAsMetric() {
        contextRunner.run(context -> {
            context.getBean("redpandaCircuitBreaker", CircuitBreaker.class);
            var meters = context.getBean(MeterRegistry.class);
            assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "redpanda").gauges()).isNotEmpty();
        });
    }
}
