package com.discord.gateway.config;

import com.discord.gateway.relay.AttachmentDownloader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class GarageConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GarageConfig.class)
            .withPropertyValues(
                    "gateway.garage.endpoint=http://localhost:3900",
                    "gateway.garage.region=us-east-1",
                    "GARAGE_ACCESS_KEY=test-access",
                    "GARAGE_SECRET_KEY=test-secret"
            );

    private GarageConfig garageConfig;

    @Mock
    private S3Client s3Client;

    @BeforeEach
    void setUp() {
        this.garageConfig = new GarageConfig();
    }

    @Test
    void shouldCreateS3ClientBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(S3Client.class);
            S3Client client = context.getBean(S3Client.class);
            assertThat(client).isNotNull();
        });
    }

    @Test
    void shouldCreateAttachmentDownloaderBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AttachmentDownloader.class);
            AttachmentDownloader downloader = context.getBean(AttachmentDownloader.class);
            assertThat(downloader).isNotNull();
        });
    }

    @Test
    void shouldDownloadFromS3UrlSuccessfully() throws Exception {
        AttachmentDownloader downloader = garageConfig.attachmentDownloader(s3Client);

        ResponseInputStream<GetObjectResponse> mockResponse = mock(ResponseInputStream.class);
        when(s3Client.getObject(any(Consumer.class))).thenReturn(mockResponse);

        // Test with a generic URL that falls to the S3 bucket logic
        String url = "http://my-s3.com/my-bucket/my-key.png";
        InputStream resultStream = downloader.download(url);

        assertThat(resultStream).isSameAs(mockResponse);

        // Verify that bucket and key were populated properly
        ArgumentCaptor<Consumer<GetObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).getObject(captor.capture());

        GetObjectRequest.Builder mockBuilder = mock(GetObjectRequest.Builder.class);
        when(mockBuilder.bucket(any())).thenReturn(mockBuilder);
        when(mockBuilder.key(any())).thenReturn(mockBuilder);

        captor.getValue().accept(mockBuilder);

        verify(mockBuilder).bucket("my-bucket");
        verify(mockBuilder).key("my-key.png");
    }

    @Test
    void shouldDecodeUrlEncodedKeys() throws Exception {
        AttachmentDownloader downloader = garageConfig.attachmentDownloader(s3Client);

        ResponseInputStream<GetObjectResponse> mockResponse = mock(ResponseInputStream.class);
        when(s3Client.getObject(any(Consumer.class))).thenReturn(mockResponse);

        // Url encoded key
        String url = "/my-bucket/my%20key%20with%20spaces.png";
        downloader.download(url);

        ArgumentCaptor<Consumer<GetObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).getObject(captor.capture());

        GetObjectRequest.Builder mockBuilder = mock(GetObjectRequest.Builder.class);
        when(mockBuilder.bucket(any())).thenReturn(mockBuilder);
        when(mockBuilder.key(any())).thenReturn(mockBuilder);

        captor.getValue().accept(mockBuilder);

        verify(mockBuilder).bucket("my-bucket");
        verify(mockBuilder).key("my key with spaces.png");
    }

    @Test
    void shouldThrowIOExceptionWhenS3UrlMissingSlash() {
        AttachmentDownloader downloader = garageConfig.attachmentDownloader(s3Client);

        // No slash to separate bucket and key
        String url = "my-bucket-my-key";
        IOException exception = assertThrows(IOException.class, () -> downloader.download(url));
        assertThat(exception.getMessage()).contains("Cannot parse bucket/key from URL");
    }

    @Test
    void shouldThrowIOExceptionWhenS3ClientFails() {
        AttachmentDownloader downloader = garageConfig.attachmentDownloader(s3Client);

        when(s3Client.getObject(any(Consumer.class))).thenThrow(new RuntimeException("S3 Error"));

        String url = "/my-bucket/my-key.png";
        IOException exception = assertThrows(IOException.class, () -> downloader.download(url));
        assertThat(exception.getMessage()).contains("Failed to download attachment");
        assertThat(exception.getCause().getMessage()).isEqualTo("S3 Error");
    }

    @Test
    void shouldAttemptHttpConnectionForDiscordUrl() {
        AttachmentDownloader downloader = garageConfig.attachmentDownloader(s3Client);

        // UnknownHostException mapping
        String url = "http://fake.discordapp.com/test";
        assertThrows(IOException.class, () -> downloader.download(url));
    }

    @Test
    void shouldAttemptHttpsConnectionForDiscordNetUrl() {
        AttachmentDownloader downloader = garageConfig.attachmentDownloader(s3Client);

        // UnknownHostException mapping (or some SSL exc)
        String url = "https://fake.discordapp.net/test";
        assertThrows(IOException.class, () -> downloader.download(url));
    }

    @Test
    void onlyDiscordHostsAreTreatedAsCdn() {
        assertThat(GarageConfig.isDiscordCdn(URI.create("https://cdn.discordapp.com/attachments/1/2/a.png"))).isTrue();
        assertThat(GarageConfig.isDiscordCdn(URI.create("https://media.discordapp.net/attachments/1/2/a.png"))).isTrue();
        assertThat(GarageConfig.isDiscordCdn(URI.create("https://discordapp.com/a.png"))).isTrue();
        assertThat(GarageConfig.isDiscordCdn(URI.create("https://evil.example/?u=cdn.discordapp.com"))).isFalse();
        assertThat(GarageConfig.isDiscordCdn(URI.create("https://discordapp.com.evil.example/a.png"))).isFalse();
        assertThat(GarageConfig.isDiscordCdn(URI.create("https://evildiscordapp.com/a.png"))).isFalse();
        assertThat(GarageConfig.isDiscordCdn(URI.create("ftp://cdn.discordapp.com/a.png"))).isFalse();
        assertThat(GarageConfig.isDiscordCdn(URI.create("/bucket/key.png"))).isFalse();
    }

    @Test
    void urlMentioningDiscordInPathIsResolvedAgainstS3() throws Exception {
        AttachmentDownloader downloader = garageConfig.attachmentDownloader(s3Client);
        ResponseInputStream<GetObjectResponse> mockResponse = mock(ResponseInputStream.class);
        when(s3Client.getObject(any(Consumer.class))).thenReturn(mockResponse);

        InputStream result = downloader.download("http://garage.internal/discord-attachments/cdn.discordapp.com.png");

        assertThat(result).isSameAs(mockResponse);
        ArgumentCaptor<Consumer<GetObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).getObject(captor.capture());
        GetObjectRequest.Builder builder = mock(GetObjectRequest.Builder.class);
        when(builder.bucket(any())).thenReturn(builder);
        when(builder.key(any())).thenReturn(builder);
        captor.getValue().accept(builder);
        verify(builder).bucket("discord-attachments");
        verify(builder).key("cdn.discordapp.com.png");
    }
}
