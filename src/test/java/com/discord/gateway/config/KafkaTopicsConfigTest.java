package com.discord.gateway.config;

import com.discord.gateway.dispatcher.ResponseDispatcher;
import com.discord.gateway.router.TopicRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicsConfigTest {

    private final TopicRegistry registry = registry();

    @Test
    void declaresEveryRoutedTopicPlusGatewayTopics() {
        var topics = KafkaTopicsConfig.topics(registry, 0, 0);

        assertThat(topics).extracting(NewTopic::name).containsExactlyInAnyOrder(
                "discord.events.message.created",
                "discord.events.interaction.command",
                KafkaTopicsConfig.COMMANDS_TOPIC,
                ResponseDispatcher.RESPONSES_TOPIC,
                ResponseDispatcher.DLT_TOPIC);
    }

    @Test
    void brokerDefaultsAreKeptWhenNotConfigured() {
        var topics = KafkaTopicsConfig.topics(registry, 0, 0);

        assertThat(topics).allSatisfy(t -> {
            assertThat(t.numPartitions()).isEqualTo(-1);
            assertThat(t.replicationFactor()).isEqualTo((short) -1);
        });
    }

    @Test
    void configuredPartitionsAndReplicasAreApplied() {
        var topics = KafkaTopicsConfig.topics(registry, 6, 3);

        assertThat(topics).allSatisfy(t -> {
            assertThat(t.numPartitions()).isEqualTo(6);
            assertThat(t.replicationFactor()).isEqualTo((short) 3);
        });
    }

    private static TopicRegistry registry() {
        var registry = new TopicRegistry();
        registry.setTopics(Map.of(
                "MESSAGE_CREATED", new TopicRegistry.TopicMapping("discord.events.message.created", "normal"),
                "INTERACTION_COMMAND", new TopicRegistry.TopicMapping("discord.events.interaction.command", "normal")));
        return registry;
    }
}
