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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
public class GarageConfig {

    @Value("${gateway.garage.endpoint}")
    private String endpoint;

    @Value("${gateway.garage.region:garage}")
    private String region;

    @Value("${GARAGE_ACCESS_KEY:local-access-key}")
    private String accessKey;

    @Value("${GARAGE_SECRET_KEY:local-secret-key}")
    private String secretKey;

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
        return url -> {
            try {
                String path = URI.create(url).getRawPath();
                if (path.startsWith("/")) path = path.substring(1);
                int slash = path.indexOf('/');
                if (slash < 0) throw new IOException("Cannot parse bucket/key from URL: " + url);
                String bucket = path.substring(0, slash);
                String key = URLDecoder.decode(path.substring(slash + 1), StandardCharsets.UTF_8);
                return (InputStream) s3Client.getObject(req -> req.bucket(bucket).key(key));
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to download attachment from S3: " + url, e);
            }
        };
    }
}
