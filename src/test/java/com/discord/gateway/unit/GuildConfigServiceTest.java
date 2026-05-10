package com.discord.gateway.unit;

import com.discord.gateway.audit.GuildConfigService;
import com.discord.gateway.domain.GuildConfig;
import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.repository.GuildConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuildConfigServiceTest {

    @Mock GuildConfigRepository repository;

    GuildConfigService service;

    @BeforeEach
    void setUp() {
        service = new GuildConfigService(repository,
                CircuitBreaker.ofDefaults("test-config"),
                new SimpleMeterRegistry());
    }

    @Test
    void upsert_newParam_savesEntityAndReturnsSuccess() {
        when(repository.findByGuildIdAndParam("g1", GuildParam.ALLOWED_CHANNELS)).thenReturn(Optional.empty());

        var result = service.upsert("g1", GuildParam.ALLOWED_CHANNELS, "*");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("ALLOWED_CHANNELS");
        verify(repository).save(any(GuildConfig.class));
    }

    @Test
    void upsert_existingParam_updatesValueWithoutSavingNewEntity() {
        var existing = new GuildConfig("g1", GuildParam.ALLOWED_CHANNELS, "old-channel");
        when(repository.findByGuildIdAndParam("g1", GuildParam.ALLOWED_CHANNELS)).thenReturn(Optional.of(existing));

        var result = service.upsert("g1", GuildParam.ALLOWED_CHANNELS, "*");

        assertThat(result.success()).isTrue();
        assertThat(existing.getValue()).isEqualTo("*");
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_invalidValue_returnsErrorWithoutTouchingRepository() {
        var result = service.upsert("g1", GuildParam.MAX_ATTACHMENT_SIZE_BYTES, "not-a-number");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("inválido");
        verifyNoInteractions(repository);
    }

    @Test
    void upsert_circuitBreakerOpen_returnsErrorMessage() {
        var openCb = CircuitBreaker.ofDefaults("open-cb-test");
        openCb.transitionToOpenState();
        var svc = new GuildConfigService(repository, openCb, new SimpleMeterRegistry());

        var result = svc.upsert("g1", GuildParam.ALLOWED_CHANNELS, "*");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Falha");
        verifyNoInteractions(repository);
    }
}
