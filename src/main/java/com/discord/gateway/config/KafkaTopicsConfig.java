package com.discord.gateway.config;

import com.discord.gateway.dispatcher.ResponseDispatcher;
import com.discord.gateway.router.TopicRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.LinkedHashSet;
import java.util.Set;

@Configuration
public class KafkaTopicsConfig {

    static final String COMMANDS_TOPIC = "discord.gateway.commands";

    @Bean
    public KafkaAdmin.NewTopics gatewayTopics(TopicRegistry topicRegistry,
                                              @Value("${gateway.kafka.topic-partitions:0}") int partitions,
                                              @Value("${gateway.kafka.topic-replicas:0}") int replicas) {
        Set<String> names = new LinkedHashSet<>();
        topicRegistry.getTopics().values().forEach(mapping -> names.add(mapping.topic()));
        names.add(COMMANDS_TOPIC);
        names.add(ResponseDispatcher.RESPONSES_TOPIC);
        names.add(ResponseDispatcher.DLT_TOPIC);
        return new KafkaAdmin.NewTopics(names.stream()
                .map(name -> topic(name, partitions, replicas))
                .toArray(NewTopic[]::new));
    }

    private static NewTopic topic(String name, int partitions, int replicas) {
        var builder = TopicBuilder.name(name);
        if (partitions > 0) builder.partitions(partitions);
        if (replicas > 0) builder.replicas(replicas);
        return builder.build();
    }
}
