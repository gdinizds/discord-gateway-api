package com.discord.gateway.audit;

import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.model.GuildInfo;
import com.discord.gateway.repository.MessageLogRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InboundEventLogServiceTest {

    private final MessageLogRepository repository = mock(MessageLogRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private InboundEventLogService service;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (service != null) service.close();
    }

    @Test
    void dropsEntriesWhenBacklogIsFullAndRecoversAfterDrain() {
        when(repository.save(any())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return inv.getArgument(0);
        });
        service = new InboundEventLogService(repository, CircuitBreaker.ofDefaults("audit-test"),
                JsonMapper.builder().build(), meterRegistry, 1);

        service.log(payload());
        service.log(payload());

        assertThat(meterRegistry.counter("discord.gateway.audit.dropped", "direction", "INBOUND").count())
                .isEqualTo(1.0);

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(meterRegistry.counter("discord.gateway.audit.written", "direction", "INBOUND").count())
                        .isEqualTo(1.0));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            service.log(payload());
            verify(repository, atLeast(2)).save(any());
        });
    }

    private static DiscordEventPayload payload() {
        return new DiscordEventPayload("MESSAGE_CREATED", UUID.randomUUID().toString(), "normal",
                GuildInfo.builder().id("guild-1").build(), "channel-1", null, null, "msg-1", 1,
                List.of(), Map.of("content", "hi"));
    }

    @Test
    void editsResolveCorrelationFromMemoryBeforeAuditIsPersisted() {
        service = new InboundEventLogService(repository, CircuitBreaker.ofDefaults("audit-cache"),
                JsonMapper.builder().build(), meterRegistry, 0);
        String correlationId = UUID.randomUUID().toString();

        service.log(new DiscordEventPayload("MESSAGE_CREATED", correlationId, "normal",
                GuildInfo.builder().id("guild-1").build(), "channel-1", null, null, "msg-42", 1,
                List.of(), Map.of()));
        service.log(new DiscordEventPayload("MESSAGE_UPDATED", correlationId, "low",
                GuildInfo.builder().id("guild-1").build(), "channel-1", null, null, "msg-42", 2,
                List.of(), Map.of()));

        var found = service.findCorrelation("msg-42");

        assertThat(found).isPresent();
        assertThat(found.get().correlationId()).hasToString(correlationId);
        assertThat(found.get().maxVersion()).isEqualTo(2);
        verifyNoInteractions(repository);
    }

    @Test
    void interactionsAreNotCached() {
        service = new InboundEventLogService(repository, CircuitBreaker.ofDefaults("audit-cache-2"),
                JsonMapper.builder().build(), meterRegistry, 0);

        service.log(new DiscordEventPayload("INTERACTION_BUTTON", UUID.randomUUID().toString(), "normal",
                GuildInfo.builder().id("guild-1").build(), "channel-1", null, "tok", "bot-msg", 1,
                List.of(), Map.of()));

        assertThat(service.cachedCorrelations()).isZero();
    }
}
