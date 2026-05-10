package com.discord.gateway.unit;

import com.discord.gateway.model.AttachmentRelayException;
import com.discord.gateway.relay.AttachmentRelayService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttachmentSizeLimitTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock S3Client s3Client;

    AttachmentRelayService relayService;

    private static final long DEFAULT_MAX = 26_214_400L; // 25 MB

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        doReturn(List.of()).when(jdbc).query(anyString(), any(Map.class), any(RowMapper.class));
        relayService = new AttachmentRelayService(
                url -> "bytes".getBytes(),
                s3Client,
                CircuitBreaker.ofDefaults("garage-test"),
                jdbc,
                new SimpleMeterRegistry(),
                DEFAULT_MAX,
                "discord-attachments",
                "http://192.168.10.96");
    }

    @Test
    void attachmentBelowGlobalLimitSucceeds() {
        assertThatNoException().isThrownBy(() ->
                relayService.relay("http://cdn.discord.com/file.txt", "file.txt",
                        DEFAULT_MAX - 1, "guild-1", "msg-1"));
    }

    @Test
    void attachmentAtGlobalLimitIsRejected() {
        assertThatThrownBy(() ->
                relayService.relay("http://cdn.discord.com/huge.bin", "huge.bin",
                        DEFAULT_MAX + 1, "guild-1", "msg-1"))
                .isInstanceOf(AttachmentRelayException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void attachmentAboveGuildSpecificLimitIsRejected() {
        long guildMax = 5_242_880L; // 5 MB
        doReturn(List.of(String.valueOf(guildMax))).when(jdbc).query(anyString(), any(Map.class), any(RowMapper.class));

        assertThatThrownBy(() ->
                relayService.relay("http://cdn.discord.com/medium.png", "medium.png",
                        guildMax + 1, "guild-special", "msg-2"))
                .isInstanceOf(AttachmentRelayException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void attachmentBelowGuildSpecificLimitSucceeds() {
        long guildMax = 5_242_880L;
        doReturn(List.of(String.valueOf(guildMax))).when(jdbc).query(anyString(), any(Map.class), any(RowMapper.class));

        assertThatNoException().isThrownBy(() ->
                relayService.relay("http://cdn.discord.com/small.png", "small.png",
                        100L, "guild-special", "msg-3"));
    }
}
