package com.discord.gateway.executor;

import net.dv8tion.jda.api.interactions.InteractionHook;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InteractionHookRegistryTest {

    private final InteractionHook hook = mock(InteractionHook.class);

    @Test
    void returnsHookRegisteredBeforeLookup() {
        var registry = new InteractionHookRegistry(Duration.ofMillis(100));
        registry.register("token-1", hook);

        assertThat(registry.getHook("token-1")).containsSame(hook);
    }

    @Test
    void waitsForHookRegisteredAfterResponseArrives() {
        var registry = new InteractionHookRegistry(Duration.ofSeconds(2));
        CompletableFuture.runAsync(() -> registry.register("token-2", hook),
                CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));

        assertThat(registry.getHook("token-2")).containsSame(hook);
    }

    @Test
    void returnsEmptyWhenHookNeverArrives() {
        var registry = new InteractionHookRegistry(Duration.ofMillis(100));

        assertThat(registry.getHook("token-3")).isEmpty();
    }

    @Test
    void lateRegistrationAfterTimeoutIsStillUsable() {
        var registry = new InteractionHookRegistry(Duration.ofMillis(50));
        assertThat(registry.getHook("token-4")).isEmpty();

        registry.register("token-4", hook);

        assertThat(registry.getHook("token-4")).containsSame(hook);
    }

    @Test
    void removedHookIsNoLongerReturned() {
        var registry = new InteractionHookRegistry(Duration.ofMillis(50));
        registry.register("token-5", hook);
        registry.remove("token-5");

        assertThat(registry.getHook("token-5")).isEmpty();
    }
}
