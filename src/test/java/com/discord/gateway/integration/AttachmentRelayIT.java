package com.discord.gateway.integration;

import com.discord.gateway.model.AttachmentRelayException;
import com.discord.gateway.relay.AttachmentRelayService;
import com.discord.gateway.repository.GuildConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AttachmentRelayIT {

    @Container
    static GenericContainer<?> localStack = new GenericContainer<>("localstack/localstack:3.5")
            .withExposedPorts(4566)
            .withEnv("SERVICES", "s3")
            .withEnv("DEFAULT_REGION", "us-east-1");

    private static final String BUCKET   = "discord-attachments";
    private static final long DEFAULT_MAX = 26_214_400L;

    static S3Client s3Client;
    static String endpoint;
    AttachmentRelayService relayService;

    @BeforeAll
    static void createS3Resources() {
        endpoint = "http://localhost:" + localStack.getMappedPort(4566);
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @BeforeEach
    void setUpService() {
        var guildConfigRepository = mock(GuildConfigRepository.class);
        when(guildConfigRepository.findByGuildIdAndParam(anyString(), any())).thenReturn(Optional.empty());

        relayService = new AttachmentRelayService(
                url -> ("content-of:" + url).getBytes(),
                s3Client,
                CircuitBreaker.ofDefaults("garage-test"),
                guildConfigRepository,
                new SimpleMeterRegistry(),
                DEFAULT_MAX,
                BUCKET,
                endpoint);
    }

    @Test
    void relayUploadsBytesToS3AndReturnsInternalUrl() {
        String internalUrl = relayService.relay(
                "https://cdn.discordapp.com/attachments/123/456/test.txt",
                "test.txt", 100L, "guild-1", "msg-1");

        assertThat(internalUrl).contains("guild-1/msg-1/test.txt");
        assertThat(internalUrl).contains(BUCKET);

        ResponseBytes<GetObjectResponse> obj = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key("guild-1/msg-1/test.txt").build());
        assertThat(new String(obj.asByteArray()))
                .isEqualTo("content-of:https://cdn.discordapp.com/attachments/123/456/test.txt");
    }

    @Test
    void attachmentAboveLimitThrowsBeforeDownload() {
        assertThatThrownBy(() ->
                relayService.relay("https://cdn.discordapp.com/huge.bin", "huge.bin",
                        DEFAULT_MAX + 1, "guild-1", "msg-2"))
                .isInstanceOf(AttachmentRelayException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void downloadFailureCausesRelayException() {
        var guildConfigRepository = mock(GuildConfigRepository.class);
        when(guildConfigRepository.findByGuildIdAndParam(anyString(), any())).thenReturn(Optional.empty());

        var failingService = new AttachmentRelayService(
                url -> { throw new java.io.IOException("Discord CDN unreachable"); },
                s3Client,
                CircuitBreaker.ofDefaults("garage-fail-test"),
                guildConfigRepository,
                new SimpleMeterRegistry(),
                DEFAULT_MAX, BUCKET, endpoint);

        assertThatThrownBy(() ->
                failingService.relay("https://cdn.discordapp.com/file.png", "file.png",
                        100L, "guild-1", "msg-3"))
                .isInstanceOf(AttachmentRelayException.class)
                .hasMessageContaining("Failed to relay");
    }

    @Test
    void garageCbOpenCausesRelayException() {
        var guildConfigRepository = mock(GuildConfigRepository.class);
        when(guildConfigRepository.findByGuildIdAndParam(anyString(), any())).thenReturn(Optional.empty());

        var openCb = CircuitBreaker.ofDefaults("garage-open-test");
        openCb.transitionToOpenState();

        var cbService = new AttachmentRelayService(
                url -> "bytes".getBytes(),
                s3Client,
                openCb,
                guildConfigRepository,
                new SimpleMeterRegistry(),
                DEFAULT_MAX, BUCKET, endpoint);

        assertThatThrownBy(() ->
                cbService.relay("https://cdn.discordapp.com/file.png", "file.png",
                        100L, "guild-1", "msg-4"))
                .isInstanceOf(AttachmentRelayException.class);
    }
}
