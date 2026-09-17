package com.discord.gateway.executor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BotMessageRegistry {

    private record Entry(String botMessageId, long timestamp) {}

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    public void register(String originMessageId, String botMessageId) {
        map.put(originMessageId, new Entry(botMessageId, System.currentTimeMillis()));
    }

    public Optional<String> getBotMessageId(String originMessageId) {
        Entry entry = map.get(originMessageId);
        return entry != null ? Optional.of(entry.botMessageId()) : Optional.empty();
    }

    @Scheduled(fixedRate = 3_600_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> now - e.getValue().timestamp() > 3_600_000);
    }
}
