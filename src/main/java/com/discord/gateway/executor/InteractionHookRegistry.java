package com.discord.gateway.executor;

import net.dv8tion.jda.api.interactions.InteractionHook;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InteractionHookRegistry {

    private record HookEntry(InteractionHook hook, long timestamp) {}

    private final ConcurrentHashMap<String, HookEntry> hooks = new ConcurrentHashMap<>();

    public void register(String interactionToken, InteractionHook hook) {
        hooks.put(interactionToken, new HookEntry(hook, System.currentTimeMillis()));
    }

    public Optional<InteractionHook> getHook(String interactionToken) {
        HookEntry entry = hooks.get(interactionToken);
        return entry != null ? Optional.of(entry.hook()) : Optional.empty();
    }

    public void remove(String interactionToken) {
        hooks.remove(interactionToken);
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupOldHooks() {
        long now = System.currentTimeMillis();
        long expirationTime = 900000;
        hooks.entrySet().removeIf(entry -> (now - entry.getValue().timestamp()) > expirationTime);
    }
}
