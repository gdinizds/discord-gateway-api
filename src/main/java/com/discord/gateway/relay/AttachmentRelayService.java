package com.discord.gateway.relay;

import com.discord.gateway.audit.GuildConfigService;
import com.discord.gateway.model.AttachmentRelayException;
import com.discord.gateway.repository.GuildConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class AttachmentRelayService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentRelayService.class);

    private final AttachmentDownloader downloader;
    private final S3Client s3Client;
    private final CircuitBreaker garageCb;
    private final GuildConfigService guildConfigService;
    private final MeterRegistry meterRegistry;
    private final long defaultMaxSizeBytes;
    private final String bucket;
    private final String endpoint;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public AttachmentRelayService(AttachmentDownloader attachmentDownloader,
                                  S3Client s3Client,
                                  CircuitBreaker garageCircuitBreaker,
                                  GuildConfigService guildConfigService,
                                  MeterRegistry meterRegistry,
                                  @Value("${gateway.attachments.max-size-bytes:26214400}") long defaultMaxSizeBytes,
                                  @Value("${gateway.garage.bucket:discord-attachments}") String bucket,
                                  @Value("${gateway.garage.endpoint}") String endpoint) {
        this.downloader = attachmentDownloader;
        this.s3Client = s3Client;
        this.garageCb = garageCircuitBreaker;
        this.guildConfigService = guildConfigService;
        this.meterRegistry = meterRegistry;
        this.defaultMaxSizeBytes = defaultMaxSizeBytes;
        this.bucket = bucket;
        this.endpoint = endpoint;
    }

    public AttachmentRelayService(AttachmentDownloader attachmentDownloader,
                                  S3Client s3Client,
                                  CircuitBreaker garageCircuitBreaker,
                                  GuildConfigRepository guildConfigRepository,
                                  MeterRegistry meterRegistry,
                                  long defaultMaxSizeBytes,
                                  String bucket,
                                  String endpoint) {
        this(attachmentDownloader, s3Client, garageCircuitBreaker,
             new GuildConfigService(guildConfigRepository, garageCircuitBreaker, meterRegistry),
             meterRegistry, defaultMaxSizeBytes, bucket, endpoint);
    }

    public record Source(String url, String fileName, long sizeBytes) {}

    public List<String> relayAll(List<Source> sources, String guildId, String messageId, long tierMaxSizeBytes) {
        if (sources.isEmpty()) return List.of();
        if (sources.size() == 1) {
            var only = sources.getFirst();
            return List.of(relay(only.url(), only.fileName(), only.sizeBytes(), guildId, messageId, tierMaxSizeBytes));
        }
        List<CompletableFuture<String>> futures = sources.stream()
                .map(src -> CompletableFuture.supplyAsync(() ->
                        relay(src.url(), src.fileName(), src.sizeBytes(), guildId, messageId, tierMaxSizeBytes), executor))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            futures.forEach(f -> f.cancel(true));
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof AttachmentRelayException relayException) throw relayException;
            throw new AttachmentRelayException("Failed to relay attachments", cause);
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private final Semaphore relaySemaphore = new Semaphore(32);

    @PreDestroy
    public void close() {
        executor.close();
    }

    public String relay(String discordUrl, String filename, long sizeBytes,
                        String guildId, String messageId, long tierMaxSizeBytes) {
        long maxSize = resolveMaxSize(guildId, tierMaxSizeBytes);
        if (sizeBytes > maxSize) {
            meterRegistry.counter("discord.gateway.attachments.discarded", "reason", "size_limit").increment();
            throw new AttachmentRelayException(
                    "Attachment '%s' size %d exceeds limit %d".formatted(filename, sizeBytes, maxSize));
        }

        String safeFilename = sanitizeFilename(filename);
        String key = guildId + "/" + messageId + "/" + safeFilename;

        try {
            if (!relaySemaphore.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS)) {
                meterRegistry.counter("discord.gateway.attachments.discarded", "reason", "relay_busy").increment();
                throw new AttachmentRelayException("Attachment relay concurrency limit reached for: " + filename);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentRelayException("Attachment relay interrupted for: " + filename, e);
        }

        int maxAttempts = 3;
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    garageCb.executeCallable(() -> {
                        try (var rawStream = downloader.download(discordUrl)) {
                            if (sizeBytes > 0) {
                                var stream = new ValidatingInputStream(rawStream, sizeBytes);
                                s3Client.putObject(
                                        PutObjectRequest.builder().bucket(bucket).key(key).contentLength(sizeBytes).build(),
                                        RequestBody.fromInputStream(stream, sizeBytes));
                            } else {
                                byte[] bytes = rawStream.readNBytes((int) maxSize + 1);
                                if (bytes.length > maxSize) {
                                    meterRegistry.counter("discord.gateway.attachments.discarded", "reason", "size_limit").increment();
                                    throw new AttachmentRelayException(
                                            "Attachment '%s' exceeded limit %d".formatted(filename, maxSize));
                                }
                                s3Client.putObject(
                                        PutObjectRequest.builder().bucket(bucket).key(key).contentLength((long) bytes.length).build(),
                                        RequestBody.fromBytes(bytes));
                            }
                        }
                        return null;
                    });
                    meterRegistry.counter("discord.gateway.attachments.relayed").increment();
                    return endpoint + "/" + bucket + "/" + key;
                } catch (AttachmentRelayException e) {
                    throw e;
                } catch (Exception e) {
                    if (e.getCause() instanceof AttachmentRelayException are) {
                        throw are;
                    }
                    if (attempt == maxAttempts) {
                        meterRegistry.counter("discord.gateway.attachments.discarded", "reason", "relay_failure").increment();
                        throw new AttachmentRelayException("Failed to relay attachment: " + filename, e);
                    }
                    log.warn("Attempt {}/{} to relay attachment '{}' failed (retrying): {}",
                            attempt, maxAttempts, filename, e.getMessage());
                    try {
                        Thread.sleep(100L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AttachmentRelayException("Attachment relay interrupted: " + filename, ie);
                    }
                }
            }
            throw new AttachmentRelayException("Failed to relay attachment: " + filename);
        } finally {
            relaySemaphore.release();
        }
    }

    private static class ValidatingInputStream extends FilterInputStream {
        private final long expectedSize;
        private long bytesRead = 0;

        ValidatingInputStream(InputStream in, long expectedSize) {
            super(in);
            this.expectedSize = expectedSize;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                bytesRead++;
            } else if (expectedSize > 0 && bytesRead < expectedSize) {
                throw new IOException("Stream truncated: expected %d bytes but read %d".formatted(expectedSize, bytesRead));
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read != -1) {
                bytesRead += read;
            } else if (expectedSize > 0 && bytesRead < expectedSize) {
                throw new IOException("Stream truncated: expected %d bytes but read %d".formatted(expectedSize, bytesRead));
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                bytesRead += skipped;
            }
            return skipped;
        }
    }

    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }
        filename = filename.replace("..", "_").strip();
        return filename.isEmpty() ? "attachment" : filename;
    }

    private long resolveMaxSize(String guildId, long tierMaxSizeBytes) {
        long fallback = tierMaxSizeBytes > 0 ? tierMaxSizeBytes : defaultMaxSizeBytes;
        if (guildConfigService == null) return fallback;
        return guildConfigService.resolveMaxAttachmentSize(guildId, fallback);
    }
}
