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

import java.io.ByteArrayInputStream;
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
                url -> new ByteArrayInputStream("bytes".getBytes()),
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
                        DEFAULT_MAX - 1, "guild-1", "msg-1", 0L));
    }

    @Test
    void attachmentAtGlobalLimitIsRejected() {
        assertThatThrownBy(() ->
                relayService.relay("http://cdn.discord.com/huge.bin", "huge.bin",
                        DEFAULT_MAX + 1, "guild-1", "msg-1", 0L))
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
                        guildMax + 1, "guild-special", "msg-2", 0L))
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
                        100L, "guild-special", "msg-3", 0L));
    }

    @Test
    void tierLimitUsedWhenNoDbConfigAndAboveDefault() {
        long tierMax = 52_428_800L; // 50 MB (boost tier 2)
        // file is 30 MB — above the 25 MB default but within tier limit
        assertThatNoException().isThrownBy(() ->
                relayService.relay("http://cdn.discord.com/large.mp4", "large.mp4",
                        DEFAULT_MAX + 1_000_000, "guild-tier2", "msg-4", tierMax));
    }

    @Test
    void tierLimitRejectedWhenExceeded() {
        long tierMax = 31_457_280L; // 30 MB
        assertThatThrownBy(() ->
                relayService.relay("http://cdn.discord.com/toobig.mp4", "toobig.mp4",
                        tierMax + 1, "guild-tier1", "msg-5", tierMax))
                .isInstanceOf(AttachmentRelayException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void dbConfigOverridesTierLimit() {
        long tierMax = 52_428_800L; // 50 MB tier
        long dbMax   = 5_242_880L;  // 5 MB admin override — stricter
        var config = new GuildConfig("guild-restricted", GuildParam.MAX_ATTACHMENT_SIZE_BYTES, String.valueOf(dbMax));
        when(guildConfigRepository.findByGuildIdAndParam("guild-restricted", GuildParam.MAX_ATTACHMENT_SIZE_BYTES))
                .thenReturn(Optional.of(config));

        assertThatThrownBy(() ->
                relayService.relay("http://cdn.discord.com/medium.mp4", "medium.mp4",
                        dbMax + 1, "guild-restricted", "msg-6", tierMax))
                .isInstanceOf(AttachmentRelayException.class)
                .hasMessageContaining("exceeds");
    }
}
