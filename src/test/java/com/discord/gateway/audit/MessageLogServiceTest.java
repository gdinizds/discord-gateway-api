package com.discord.gateway.audit;

import com.discord.gateway.domain.MessageLog;
import com.discord.gateway.model.DispatchResult;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.repository.MessageLogRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MessageLogServiceTest {

    @Mock private MessageLogRepository messageLogRepository;
    @Mock private ObjectMapper objectMapper;

    private MeterRegistry meterRegistry;
    private MessageLogService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        CircuitBreaker cb = CircuitBreaker.ofDefaults("test-db");
        service = new MessageLogService(messageLogRepository, cb, objectMapper, meterRegistry);
    }

    @Test
    void shouldLogOutboundMessageAsync() throws Exception {
        OutboundResponsePayload payload = new OutboundResponsePayload(
                "cor-1", "interaction", "int-1", "tok-1", Map.of()
        );
        DispatchResult result = new DispatchResult(true, "msg-123", "chan-123", null);
        
        when(objectMapper.writeValueAsString(any())).thenReturn("{\\\"audit\\\":true}");

        service.logOutbound(payload, result, "guild-1", "user-1");

        // We must sleep slightly as the execution is async (virtual threads)
        Thread.sleep(100);

        ArgumentCaptor<MessageLog> captor = ArgumentCaptor.forClass(MessageLog.class);
        verify(messageLogRepository, timeout(1000)).save(captor.capture());

        MessageLog saved = captor.getValue();
        assertThat(saved.getGuildId()).isEqualTo("guild-1");
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getDiscordMessageId()).isEqualTo("msg-123");
        assertThat(saved.getDiscordChannelId()).isEqualTo("chan-123");
        assertThat(saved.getResponseType()).isEqualTo("interaction");
    }

    @Test
    void shouldNotThrowExceptionIfLoggingFails() throws Exception {
        OutboundResponsePayload payload = new OutboundResponsePayload(
                "cor-1", "interaction", "int-1", "tok-1", Map.of()
        );
        DispatchResult result = new DispatchResult(false, null, null, "error");

        doThrow(new RuntimeException("DB offline")).when(messageLogRepository).save(any());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // Calling it shouldn't bubble the Exception from the executor
        service.logOutbound(payload, result, "guild-1", "user-1");

        Thread.sleep(100);
        verify(messageLogRepository, timeout(1000)).save(any());
        // Since it's a swallow-on-executor design, the caller feels no impact.
    }
}