package com.discord.gateway.unit;

import com.discord.gateway.domain.GuildConfig;
import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.model.AttachmentRelayException;
import com.discord.gateway.relay.AttachmentRelayService;
import com.discord.gateway.repository.GuildConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentSizeLimitTest {

    @Mock GuildConfigRepository guildConfigRepository;
    @Mock S3Client s3Client;

    AttachmentRelayService relayService;

    private static final long DEFAULT_MAX = 26_214_400L; // 25 MB

    @BeforeEach
    void setUp() {
        when(guildConfigRepository.findByGuildIdAndParam(anyString(), any())).thenReturn(Optional.empty());
        relayService = new AttachmentRelayService(
                url -> "bytes".getBytes(),
                s3Client,
                CircuitBreaker.ofDefaults("garage-test"),
                guildConfigRepository,
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
    void attachmentAboveGuildSpecificLimitIsRejected() {
        long guildMax = 5_242_880L; // 5 MB
        var config = new GuildConfig("guild-special", GuildParam.MAX_ATTACHMENT_SIZE_BYTES, String.valueOf(guildMax));
        when(guildConfigRepository.findByGuildIdAndParam("guild-special", GuildParam.MAX_ATTACHMENT_SIZE_BYTES))
                .thenReturn(Optional.of(config));

        assertThatThrownBy(() ->
                relayService.relay("http://cdn.discord.com/medium.png", "medium.png",
                        guildMax + 1, "guild-special", "msg-2"))
                .isInstanceOf(AttachmentRelayException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void attachmentBelowGuildSpecificLimitSucceeds() {
        long guildMax = 5_242_880L;
        var config = new GuildConfig("guild-special", GuildParam.MAX_ATTACHMENT_SIZE_BYTES, String.valueOf(guildMax));
        when(guildConfigRepository.findByGuildIdAndParam("guild-special", GuildParam.MAX_ATTACHMENT_SIZE_BYTES))
                .thenReturn(Optional.of(config));

        assertThatNoException().isThrownBy(() ->
                relayService.relay("http://cdn.discord.com/small.png", "small.png",
                        100L, "guild-special", "msg-3"));
    }
}
