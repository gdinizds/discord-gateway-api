package com.discord.gateway.relay;

import com.discord.gateway.model.AttachmentRelayException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.List;
import java.util.Map;

@Service
public class AttachmentRelayService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentRelayService.class);

    private static final String GUILD_MAX_SIZE_SQL = """
            SELECT value FROM gateway.guild_config
            WHERE guild_id = :guildId AND param = 'MAX_ATTACHMENT_SIZE_BYTES'
            """;

    private final AttachmentDownloader downloader;
    private final S3Client s3Client;
    private final CircuitBreaker garageCb;
    private final NamedParameterJdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;
    private final long defaultMaxSizeBytes;
    private final String bucket;
    private final String endpoint;

    public AttachmentRelayService(AttachmentDownloader attachmentDownloader,
                                  S3Client s3Client,
                                  CircuitBreaker garageCircuitBreaker,
                                  NamedParameterJdbcTemplate jdbc,
                                  MeterRegistry meterRegistry,
                                  @Value("${gateway.attachments.max-size-bytes:26214400}") long defaultMaxSizeBytes,
                                  @Value("${gateway.garage.bucket:discord-attachments}") String bucket,
                                  @Value("${gateway.garage.endpoint}") String endpoint) {
        this.downloader = attachmentDownloader;
        this.s3Client = s3Client;
        this.garageCb = garageCircuitBreaker;
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
        this.defaultMaxSizeBytes = defaultMaxSizeBytes;
        this.bucket = bucket;
        this.endpoint = endpoint;
    }

    public String relay(String discordUrl, String filename, long sizeBytes, String guildId, String messageId) {
        long maxSize = resolveMaxSize(guildId);
        if (sizeBytes > maxSize) {
            meterRegistry.counter("discord.gateway.attachments.discarded", "reason", "size_limit").increment();
            throw new AttachmentRelayException(
                    "Attachment '%s' size %d exceeds limit %d".formatted(filename, sizeBytes, maxSize));
        }

        String key = guildId + "/" + messageId + "/" + filename;
        try {
            garageCb.executeCallable(() -> {
                byte[] bytes = downloader.download(discordUrl);
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

    private long resolveMaxSize(String guildId) {
        if (guildId == null) return defaultMaxSizeBytes;
        try {
            List<String> result = jdbc.query(GUILD_MAX_SIZE_SQL,
                    Map.of("guildId", guildId),
                    (rs, _) -> rs.getString("value"));
            if (!result.isEmpty()) return Long.parseLong(result.get(0));
        } catch (Exception e) {
            log.warn("Failed to resolve guild-specific max size for guild {}, using default", guildId, e);
        }
        return defaultMaxSizeBytes;
    }
}
