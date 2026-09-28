package com.discord.gateway.executor;

import net.dv8tion.jda.api.interactions.InteractionHook;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class InteractionHookRegistry {

    static final Duration DEFAULT_WAIT = Duration.ofSeconds(3);
    private static final long EXPIRATION_MS = 900_000;

    private record HookEntry(CompletableFuture<InteractionHook> hook, long timestamp) {}

    private final ConcurrentHashMap<String, HookEntry> hooks = new ConcurrentHashMap<>();
    private final Duration wait;

    public InteractionHookRegistry() {
        this(DEFAULT_WAIT);
    }

    InteractionHookRegistry(Duration wait) {
        this.wait = wait;
    }

    public void register(String interactionToken, InteractionHook hook) {
        hooks.computeIfAbsent(interactionToken, t -> newEntry()).hook().complete(hook);
    }

    public Optional<InteractionHook> getHook(String interactionToken) {
        HookEntry entry = hooks.computeIfAbsent(interactionToken, t -> newEntry());
        try {
            return Optional.of(entry.hook().get(wait.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            hooks.remove(interactionToken, entry);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        }
    }

    public void remove(String interactionToken) {
        hooks.remove(interactionToken);
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupOldHooks() {
        long now = System.currentTimeMillis();
        hooks.entrySet().removeIf(entry -> (now - entry.getValue().timestamp()) > EXPIRATION_MS);
    }

    private static HookEntry newEntry() {
        return new HookEntry(new CompletableFuture<>(), System.currentTimeMillis());
    }
}
