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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
    public AttachmentDownloader attachmentDownloader() {
        var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            try {
                var response = client.send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2)
                    throw new IOException("HTTP " + response.statusCode() + " from " + url);
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted fetching " + url, e);
            }
        };
    }
}
