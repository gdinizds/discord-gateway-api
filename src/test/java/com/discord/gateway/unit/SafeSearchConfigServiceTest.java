package com.discord.gateway.unit;

import com.discord.gateway.audit.SafeSearchConfigService;
import com.discord.gateway.config.KafkaTopicsConfig;
import com.discord.gateway.domain.ConfigAuditLog;
import com.discord.gateway.domain.SafeSearchConfig;
import com.discord.gateway.repository.ConfigAuditLogRepository;
import com.discord.gateway.repository.SafeSearchConfigRepository;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SafeSearchConfigServiceTest {

    @Mock SafeSearchConfigRepository configRepository;
    @Mock ConfigAuditLogRepository auditLogRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    ObjectMapper objectMapper = new ObjectMapper();
    CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    SafeSearchConfigService service;

    @BeforeEach
    void setUp() {
        service = new SafeSearchConfigService(
                configRepository,
                auditLogRepository,
                kafkaTemplate,
                objectMapper,
                circuitBreaker,
                meterRegistry
        );
    }

    @Test
    void setGuildSafeSearch_newRecord_savesAndPublishesEvent() {
        when(configRepository.findByGuildIdAndChannelId("guild-1", "")).thenReturn(Optional.empty());

        var result = service.setGuildSafeSearch("guild-1", "user-admin", 1);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("1: Moderate");

        var configCaptor = ArgumentCaptor.forClass(SafeSearchConfig.class);
        verify(configRepository).save(configCaptor.capture());
        assertThat(configCaptor.getValue().getGuildId()).isEqualTo("guild-1");
        assertThat(configCaptor.getValue().getChannelId()).isEqualTo("");
        assertThat(configCaptor.getValue().getLevel()).isEqualTo(1);
        assertThat(configCaptor.getValue().getUpdatedBy()).isEqualTo("user-admin");

        var auditCaptor = ArgumentCaptor.forClass(ConfigAuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getGuildId()).isEqualTo("guild-1");
        assertThat(auditCaptor.getValue().getChannelId()).isNull();
        assertThat(auditCaptor.getValue().getUserId()).isEqualTo("user-admin");
        assertThat(auditCaptor.getValue().getSettingKey()).isEqualTo("safesearch_guild");
        assertThat(auditCaptor.getValue().getOldValue()).isNull();
        assertThat(auditCaptor.getValue().getNewValue()).isEqualTo("1");

        verify(kafkaTemplate).send(eq(KafkaTopicsConfig.CONFIG_UPDATED_TOPIC), eq("guild-1"), anyString());
    }

    @Test
    void setGuildSafeSearch_existingRecord_updatesAndLogsOldValue() {
        var existing = new SafeSearchConfig("guild-1", "", 2, "initial-user");
        when(configRepository.findByGuildIdAndChannelId("guild-1", "")).thenReturn(Optional.of(existing));

        var result = service.setGuildSafeSearch("guild-1", "user-admin", 0);

        assertThat(result.success()).isTrue();
        assertThat(existing.getLevel()).isEqualTo(0);
        assertThat(existing.getUpdatedBy()).isEqualTo("user-admin");

        var auditCaptor = ArgumentCaptor.forClass(ConfigAuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOldValue()).isEqualTo("2");
        assertThat(auditCaptor.getValue().getNewValue()).isEqualTo("0");

        verify(kafkaTemplate).send(eq(KafkaTopicsConfig.CONFIG_UPDATED_TOPIC), eq("guild-1"), anyString());
    }

    @Test
    void setChannelSafeSearch_newRecord_savesWithChannelId() {
        when(configRepository.findByGuildIdAndChannelId("guild-1", "channel-100")).thenReturn(Optional.empty());

        var result = service.setChannelSafeSearch("guild-1", "channel-100", "user-admin", 2);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("<#channel-100>");
        assertThat(result.message()).contains("2: Strict");

        var configCaptor = ArgumentCaptor.forClass(SafeSearchConfig.class);
        verify(configRepository).save(configCaptor.capture());
        assertThat(configCaptor.getValue().getGuildId()).isEqualTo("guild-1");
        assertThat(configCaptor.getValue().getChannelId()).isEqualTo("channel-100");
        assertThat(configCaptor.getValue().getLevel()).isEqualTo(2);

        var auditCaptor = ArgumentCaptor.forClass(ConfigAuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getChannelId()).isEqualTo("channel-100");
        assertThat(auditCaptor.getValue().getSettingKey()).isEqualTo("safesearch_channel");

        verify(kafkaTemplate).send(eq(KafkaTopicsConfig.CONFIG_UPDATED_TOPIC), eq("channel-100"), anyString());
    }

    @Test
    void invalidLevel_returnsError() {
        var resultNegative = service.setGuildSafeSearch("guild-1", "user-admin", -1);
        assertThat(resultNegative.success()).isFalse();

        var resultTooHigh = service.setChannelSafeSearch("guild-1", "chan-1", "user-admin", 3);
        assertThat(resultTooHigh.success()).isFalse();

        verifyNoInteractions(configRepository);
        verifyNoInteractions(auditLogRepository);
        verifyNoInteractions(kafkaTemplate);
    }
}
