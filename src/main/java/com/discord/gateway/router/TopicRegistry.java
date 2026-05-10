package com.discord.gateway.router;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "gateway")
public class TopicRegistry {

    public record TopicMapping(String topic, String priority) {}

    private Map<String, TopicMapping> topics = new HashMap<>();

    public void setTopics(Map<String, TopicMapping> topics) {
        this.topics = topics;
    }

    public TopicMapping get(String eventType) {
        return topics.get(eventType);
    }

    public boolean isInteraction(String eventType) {
        return eventType != null && eventType.startsWith("INTERACTION_");
    }
}
