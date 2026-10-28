package com.discord.gateway.config;

import com.discord.gateway.relay.AttachmentDownloader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Configuration
public class GarageConfig {

    private static final List<String> DISCORD_CDN_DOMAINS = List.of("discordapp.com", "discordapp.net");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    @Value("${gateway.garage.endpoint}")
    private String endpoint;

    @Value("${gateway.garage.region:garage}")
    private String region;

    @Value("${gateway.garage.bucket:discord-attachments}")
    private String bucket = "discord-attachments";

    @Value("${GARAGE_ACCESS_KEY:local-access-key}")
    private String accessKey;

    @Value("${GARAGE_SECRET_KEY:local-secret-key}")
    private String secretKey;

    void setBucket(String bucket) {
        this.bucket = bucket;
    }

    String getBucket() {
        return this.bucket;
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
    }

    @Bean
    public AttachmentDownloader attachmentDownloader(S3Client s3Client) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();

        return url -> {
            try {
                URI uri = URI.create(url);
                if (isDiscordCdn(uri)) {
                    if (!"https".equalsIgnoreCase(uri.getScheme())) {
                        throw new IOException("Insecure scheme: HTTPS required for Discord CDN");
                    }
                    validateHostNotPrivate(uri.getHost());
                    HttpRequest request = HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                            .header("User-Agent", "DiscordBot (Gateway, 1.0)")
                            .GET()
                            .build();
                    HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() >= 300 && response.statusCode() < 400) {
                        throw new IOException("HTTP redirect blocked for security: " + response.statusCode());
                    }
                    if (response.statusCode() >= 400) {
                        throw new IOException("HTTP request failed with status: " + response.statusCode());
                    }
                    return response.body();
                }

                String path = uri.getRawPath();
                if (path == null) throw new IOException("Cannot parse bucket/key from URL: " + url);
                if (path.startsWith("/")) path = path.substring(1);
                int slash = path.indexOf('/');
                if (slash < 0) throw new IOException("Cannot parse bucket/key from URL: " + url);
                String extractedBucket = path.substring(0, slash);
                if (this.bucket != null && !this.bucket.isBlank() && !this.bucket.equals(extractedBucket)) {
                    throw new IOException("Unauthorized bucket: " + extractedBucket);
                }
                String key = URLDecoder.decode(path.substring(slash + 1), StandardCharsets.UTF_8);
                if (key.contains("..") || key.startsWith("/")) {
                    throw new IOException("Invalid object key (path traversal detected): " + key);
                }
                return (InputStream) s3Client.getObject(req -> req.bucket(extractedBucket).key(key));
            } catch (IOException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted: " + url, e);
            } catch (Exception e) {
                throw new IOException("Failed to download attachment: " + url, e);
            }
        };
    }

    private static void validateHostNotPrivate(String host) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IOException("Host cannot be null or empty");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress() || isUniqueLocal(addr)) {
                    throw new IOException("Access to private/local address is blocked: " + host + " -> " + addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new IOException("Unable to resolve host: " + host, e);
        }
    }

    private static boolean isUniqueLocal(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & (byte) 0xfe) == (byte) 0xfc;
    }

    static boolean isDiscordCdn(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) return false;
        if (!"https".equalsIgnoreCase(scheme)) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return DISCORD_CDN_DOMAINS.stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d));
    }
}

