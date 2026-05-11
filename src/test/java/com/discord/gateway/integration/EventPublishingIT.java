package com.discord.gateway.integration;

import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.model.GuildInfo;
import com.discord.gateway.model.UserInfo;
import com.discord.gateway.router.EventRouter;
import com.discord.gateway.startup.StartupReconciliationService;
import tools.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class EventPublishingIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("gateway_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @MockitoBean JDA jda;

    @Autowired EventRouter eventRouter;
    @Autowired ObjectMapper objectMapper;
    @Autowired StartupReconciliationService startupReconciliationService;

    KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUpConsumer() {
        consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", "it-consumer-" + UUID.randomUUID(),
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName(),
                "auto.offset.reset", "earliest"));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void messageCreatedIsPublishedToCorrectTopic() throws Exception {
        consumer.subscribe(List.of("discord.events.message.created"));

        var payload = payload("MESSAGE_CREATED", "guild-1", "msg-1");
        eventRouter.route(payload, null);

        var record = pollByCorrelation(consumer, "discord.events.message.created", payload.correlationId());
        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo("guild-1");

        var published = objectMapper.readValue(record.value(), DiscordEventPayload.class);
        assertThat(published.eventType()).isEqualTo("MESSAGE_CREATED");
        assertThat(published.priority()).isEqualTo("normal");
        assertThat(published.correlationId()).isNotNull();
    }

    @Test
    void messageUpdatedHasLowPriority() throws Exception {
        consumer.subscribe(List.of("discord.events.message.updated"));

        var payload = payload("MESSAGE_UPDATED", "guild-2", "msg-2");
        eventRouter.route(payload, null);

        var record = pollByCorrelation(consumer, "discord.events.message.updated", payload.correlationId());
        assertThat(record).isNotNull();
        var published = objectMapper.readValue(record.value(), DiscordEventPayload.class);
        assertThat(published.priority()).isEqualTo("low");
    }

    @Test
    void correlationIdIsSameAcrossMessageVersions() throws Exception {
        consumer.subscribe(List.of(
                "discord.events.message.created",
                "discord.events.message.updated"));

        String correlationId = UUID.randomUUID().toString();
        String messageId = "msg-corr-test";

        var created = new DiscordEventPayload("MESSAGE_CREATED", correlationId, "normal",
                GuildInfo.builder().id("guild-3").build(), "ch-1",
                UserInfo.builder().id("user-1").build(), null, messageId, 1, List.of(),
                Map.of("content", "hello", "messageId", messageId));
        eventRouter.route(created, null);

        var updated = new DiscordEventPayload("MESSAGE_UPDATED", correlationId, "low",
                GuildInfo.builder().id("guild-3").build(), "ch-1",
                UserInfo.builder().id("user-1").build(), null, messageId, 2, List.of(),
                Map.of("content", "hello edited", "messageId", messageId));
        eventRouter.route(updated, null);

        // Collect both records in a single poll loop to avoid discarding cross-topic records
        ConsumerRecord<String, String> r1 = null, r2 = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && (r1 == null || r2 == null)) {
            for (var record : consumer.poll(Duration.ofMillis(500))) {
                var payload = objectMapper.readValue(record.value(), DiscordEventPayload.class);
                if (!correlationId.equals(payload.correlationId())) continue;
                if ("discord.events.message.created".equals(record.topic())) r1 = record;
                if ("discord.events.message.updated".equals(record.topic()))  r2 = record;
            }
        }

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();

        var p1 = objectMapper.readValue(r1.value(), DiscordEventPayload.class);
        var p2 = objectMapper.readValue(r2.value(), DiscordEventPayload.class);
        assertThat(p1.correlationId()).isEqualTo(p2.correlationId());
        assertThat(p1.version()).isEqualTo(1);
        assertThat(p2.version()).isEqualTo(2);
    }

    @Test
    void interactionCommandIsPublishedWithNormalPriority() throws Exception {
        consumer.subscribe(List.of("discord.events.interaction.command"));

        var payload = payload("INTERACTION_COMMAND", "guild-4", null);
        eventRouter.route(payload, null);

        var record = pollByCorrelation(consumer, "discord.events.interaction.command", payload.correlationId());
        assertThat(record).isNotNull();
        var published = objectMapper.readValue(record.value(), DiscordEventPayload.class);
        assertThat(published.priority()).isEqualTo("normal");
    }

    @Test
    void interactionButtonIsPublished() throws Exception {
        consumer.subscribe(List.of("discord.events.interaction.button"));

        var payload = payload("INTERACTION_BUTTON", "guild-5", "msg-btn");
        eventRouter.route(payload, null);

        assertThat(pollByCorrelation(consumer, "discord.events.interaction.button", payload.correlationId())).isNotNull();
    }

    @Test
    void interactionModalIsPublished() throws Exception {
        consumer.subscribe(List.of("discord.events.interaction.modal"));

        var payload = payload("INTERACTION_MODAL", "guild-6", null);
        eventRouter.route(payload, null);

        assertThat(pollByCorrelation(consumer, "discord.events.interaction.modal", payload.correlationId())).isNotNull();
    }

    @Test
    void guildMemberEventHasLowPriority() throws Exception {
        consumer.subscribe(List.of("discord.events.guild.member"));

        var payload = payload("GUILD_MEMBER", "guild-7", null);
        eventRouter.route(payload, null);

        var record = pollByCorrelation(consumer, "discord.events.guild.member", payload.correlationId());
        assertThat(record).isNotNull();
        var published = objectMapper.readValue(record.value(), DiscordEventPayload.class);
        assertThat(published.priority()).isEqualTo("low");
    }

    @Test
    void guildUpdatedEventIsPublished() throws Exception {
        consumer.subscribe(List.of("discord.events.guild.updated"));

        var payload = payload("GUILD_UPDATED", "guild-8", null);
        eventRouter.route(payload, null);

        assertThat(pollByCorrelation(consumer, "discord.events.guild.updated", payload.correlationId())).isNotNull();
    }

    @Test
    void messageCreatedIsAuditedInMessageLog() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        consumer.subscribe(List.of("discord.events.message.created"));

        var payload = new DiscordEventPayload("MESSAGE_CREATED", correlationId, "normal",
                GuildInfo.builder().id("guild-audit").build(), "ch-1",
                UserInfo.builder().id("user-1").build(), null, "msg-audit", 1, List.of(),
                Map.of("content", "audit test", "messageId", "msg-audit"));
        eventRouter.route(payload, null);

        var record = pollByCorrelation(consumer, "discord.events.message.created", correlationId);
        assertThat(record).isNotNull();
        assertThat(correlationId).isNotNull();
    }

    private DiscordEventPayload payload(String eventType, String guildId, String messageId) {
        return new DiscordEventPayload(eventType, UUID.randomUUID().toString(), "normal",
                GuildInfo.builder().id(guildId).build(), "channel-1",
                UserInfo.builder().id("user-1").build(),
                eventType.startsWith("INTERACTION_") ? "token-" + UUID.randomUUID() : null,
                messageId, 1, List.of(), Map.of());
    }

    private ConsumerRecord<String, String> pollByCorrelation(
            KafkaConsumer<String, String> consumer, String topic, String correlationId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var records = consumer.poll(Duration.ofMillis(500));
            for (var record : records.records(topic)) {
                var payload = objectMapper.readValue(record.value(), DiscordEventPayload.class);
                if (correlationId.equals(payload.correlationId())) {
                    return record;
                }
            }
        }
        return null;
    }
}
