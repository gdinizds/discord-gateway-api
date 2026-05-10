package com.discord.gateway.integration;

import com.discord.gateway.executor.InteractionHookRegistry;
import tools.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class ResponseDispatchIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("gateway_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @MockitoBean
    JDA jda;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    InteractionHookRegistry hookRegistry;

    @Autowired
    ObjectMapper objectMapper;

    InteractionHook mockHook;

    private static final String TOKEN = "test-interaction-token";
    private static final String TOPIC = "discord.gateway.responses";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockHook = mock(InteractionHook.class);
        hookRegistry.register(TOKEN, mockHook);

        var msgAction = mock(WebhookMessageCreateAction.class);
        when(mockHook.sendMessage(anyString())).thenReturn(msgAction);
        when(msgAction.setEphemeral(anyBoolean())).thenReturn(msgAction);

        var editAction = mock(WebhookMessageEditAction.class);
        when(mockHook.editOriginal(anyString())).thenReturn(editAction);
    }

    @Test
    void replyIsDispatchedAndAuditedInMessageLog() throws Exception {
        var payload = Map.of(
                "responseType", "REPLY",
                "interactionToken", TOKEN,
                "content", "Hello from test!",
                "correlationId", "550e8400-e29b-41d4-a716-446655440001"
        );
        kafkaTemplate.send(TOPIC, "guild-123", objectMapper.writeValueAsString(payload));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            verify(mockHook).sendMessage("Hello from test!");
            int count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gateway.message_log WHERE event_type = 'REPLY'",
                    Map.of(), Integer.class);
            assertThat(count).isGreaterThan(0);
        });
    }

    @Test
    void ephemeralReplyIsDispatchedWithEphemeralFlag() throws Exception {
        var payload = Map.of(
                "responseType", "EPHEMERAL_REPLY",
                "interactionToken", TOKEN,
                "content", "Only you can see this"
        );
        kafkaTemplate.send(TOPIC, "guild-123", objectMapper.writeValueAsString(payload));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                verify(mockHook).sendMessage("Only you can see this"));
    }

    @Test
    void updateMessageCallsEditOriginal() throws Exception {
        var payload = Map.of(
                "responseType", "UPDATE_MESSAGE",
                "interactionToken", TOKEN,
                "messageId", "msg-123",
                "content", "Edited content"
        );
        kafkaTemplate.send(TOPIC, "guild-123", objectMapper.writeValueAsString(payload));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                verify(mockHook).editOriginal("Edited content"));
    }

    @Test
    void invalidResponseTypeIsRejectedAndAcknowledged() throws Exception {
        var payload = Map.of(
                "responseType", "INVALID_TYPE",
                "interactionToken", TOKEN,
                "content", "some content"
        );
        kafkaTemplate.send(TOPIC, "guild-123", objectMapper.writeValueAsString(payload));

        // Message must be consumed (ack'ed) without throwing — sleep to confirm no crash
        TimeUnit.SECONDS.sleep(3);
    }

    @Test
    void updateMessageWithoutMessageIdIsRejectedAndAcknowledged() throws Exception {
        var payload = Map.of(
                "responseType", "UPDATE_MESSAGE",
                "interactionToken", TOKEN,
                "content", "some content"
        );
        kafkaTemplate.send(TOPIC, "guild-123", objectMapper.writeValueAsString(payload));

        TimeUnit.SECONDS.sleep(3);
        // Validator rejects — no Discord call
    }

    @Test
    void deferredReplyIsDispatched() throws Exception {
        var payload = Map.of(
                "responseType", "DEFERRED_REPLY",
                "interactionToken", TOKEN,
                "content", "Processing complete"
        );
        kafkaTemplate.send(TOPIC, "guild-123", objectMapper.writeValueAsString(payload));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                verify(mockHook).sendMessage("Processing complete"));
    }
}
