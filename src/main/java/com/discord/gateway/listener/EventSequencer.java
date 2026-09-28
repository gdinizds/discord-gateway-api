package com.discord.gateway.listener;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class EventSequencer {

    private static final Logger log = LoggerFactory.getLogger(EventSequencer.class);

    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    @Autowired
    public EventSequencer(MeterRegistry meterRegistry) {
        this(Executors.newVirtualThreadPerTaskExecutor());
        Gauge.builder("discord.gateway.sequencer.active_keys", tails, ConcurrentHashMap::size)
                .register(meterRegistry);
    }

    private EventSequencer(ExecutorService executor) {
        this.executor = executor;
    }

    public static EventSequencer direct() {
        return new EventSequencer((ExecutorService) null);
    }

    public void submit(String key, Runnable task) {
        Runnable safe = guarded(task);
        if (executor == null) {
            safe.run();
            return;
        }
        if (key == null) {
            executor.execute(safe);
            return;
        }
        CompletableFuture<Void> next = tails.compute(key, (k, tail) ->
                tail == null ? CompletableFuture.runAsync(safe, executor) : tail.exceptionally(e -> null).thenRunAsync(safe, executor));
        next.whenComplete((r, e) -> tails.remove(key, next));
    }

    int activeKeys() {
        return tails.size();
    }

    @PreDestroy
    public void close() {
        if (executor != null) executor.close();
    }

    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Unhandled error while processing Discord event", e);
            } finally {
                MDC.clear();
            }
        };
    }
}
