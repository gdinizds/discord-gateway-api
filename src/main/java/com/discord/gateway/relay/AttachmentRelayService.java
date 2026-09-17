package com.discord.gateway.relay;

import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.model.AttachmentRelayException;
import com.discord.gateway.repository.GuildConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class AttachmentRelayService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentRelayService.class);

    private final AttachmentDownloader downloader;
    private final S3Client s3Client;
    private final CircuitBreaker garageCb;
    private final GuildConfigRepository guildConfigRepository;
    private final MeterRegistry meterRegistry;
    private final long defaultMaxSizeBytes;
    private final String bucket;
    private final String endpoint;

    public AttachmentRelayService(AttachmentDownloader attachmentDownloader,
                                  S3Client s3Client,
                                  CircuitBreaker garageCircuitBreaker,
                                  GuildConfigRepository guildConfigRepository,
                                  MeterRegistry meterRegistry,
                                  @Value("${gateway.attachments.max-size-bytes:26214400}") long defaultMaxSizeBytes,
                                  @Value("${gateway.garage.bucket:discord-attachments}") String bucket,
                                  @Value("${gateway.garage.endpoint}") String endpoint) {
        this.downloader = attachmentDownloader;
        this.s3Client = s3Client;
        this.garageCb = garageCircuitBreaker;
        this.guildConfigRepository = guildConfigRepository;
        this.meterRegistry = meterRegistry;
        this.defaultMaxSizeBytes = defaultMaxSizeBytes;
        this.bucket = bucket;
        this.endpoint = endpoint;
    }

    public String relay(String discordUrl, String filename, long sizeBytes,
                        String guildId, String messageId, long tierMaxSizeBytes) {
        long maxSize = resolveMaxSize(guildId, tierMaxSizeBytes);
        if (sizeBytes > maxSize) {
            meterRegistry.counter("discord.gateway.attachments.discarded", "reason", "size_limit").increment();
            throw new AttachmentRelayException(
                    "Attachment '%s' size %d exceeds limit %d".formatted(filename, sizeBytes, maxSize));
        }

        String key = guildId + "/" + messageId + "/" + filename;
        try {
            garageCb.executeCallable(() -> {
                byte[] bytes;
                try (var stream = downloader.download(discordUrl)) {
                    bytes = stream.readAllBytes();
                }
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentLength((long) bytes.length)
                                .build(),
                        RequestBody.fromBytes(bytes));
                return null;
            });
            meterRegistry.counter("discord.gateway.attachments.relayed").increment();
            return endpoint + "/" + bucket + "/" + key;
        } catch (Exception e) {
            meterRegistry.counter("discord.gateway.attachments.discarded", "reason", "relay_failure").increment();
            throw new AttachmentRelayException("Failed to relay attachment: " + filename, e);
        }
    }

    private long resolveMaxSize(String guildId, long tierMaxSizeBytes) {
        long fallback = tierMaxSizeBytes > 0 ? tierMaxSizeBytes : defaultMaxSizeBytes;
        if (guildId == null) return fallback;
        try {
            return guildConfigRepository
                    .findByGuildIdAndParam(guildId, GuildParam.MAX_ATTACHMENT_SIZE_BYTES)
                    .map(c -> Long.parseLong(c.getValue()))
                    .orElse(fallback);
        } catch (Exception e) {
            log.warn("Failed to resolve guild-specific max size for guild {}, using tier/default", guildId, e);
            return fallback;
        }
    }
}
