package com.discord.gateway.executor;

import net.dv8tion.jda.api.interactions.InteractionHook;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InteractionHookRegistry {

    private final ConcurrentHashMap<String, InteractionHook> hooks = new ConcurrentHashMap<>();

    public void register(String interactionToken, InteractionHook hook) {
        hooks.put(interactionToken, hook);
    }

    public Optional<InteractionHook> getHook(String interactionToken) {
        return Optional.ofNullable(hooks.get(interactionToken));
    }

    public void remove(String interactionToken) {
        hooks.remove(interactionToken);
    }
}
