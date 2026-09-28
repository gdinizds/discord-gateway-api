package com.discord.gateway.listener;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventSequencerTest {

    private final EventSequencer sequencer = new EventSequencer(new SimpleMeterRegistry());

    @AfterEach
    void tearDown() {
        sequencer.close();
    }

    @Test
    void tasksWithSameKeyRunInSubmissionOrder() {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 200; i++) {
            int n = i;
            sequencer.submit("channel-1", () -> {
                if (ThreadLocalRandom.current().nextInt(10) == 0) sleep(2);
                seen.add(n);
            });
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> seen.size() == 200);
        assertThat(seen).containsExactlyElementsOf(IntStream.range(0, 200).boxed().toList());
    }

    @Test
    void slowKeyDoesNotBlockOtherKeys() throws Exception {
        var blocker = new CountDownLatch(1);
        var otherDone = new CountDownLatch(1);

        sequencer.submit("slow", () -> awaitLatch(blocker));
        sequencer.submit("fast", otherDone::countDown);

        assertThat(otherDone.await(5, TimeUnit.SECONDS)).isTrue();
        blocker.countDown();
    }

    @Test
    void failingTaskDoesNotBreakTheChain() {
        var done = new CountDownLatch(1);

        sequencer.submit("channel-2", () -> { throw new IllegalStateException("boom"); });
        sequencer.submit("channel-2", done::countDown);

        await().atMost(Duration.ofSeconds(5)).until(() -> done.getCount() == 0);
    }

    @Test
    void keysAreReleasedWhenIdle() {
        for (int i = 0; i < 50; i++) {
            sequencer.submit("key-" + i, () -> sleep(1));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> sequencer.activeKeys() == 0);
    }

    @Test
    void directSequencerRunsInline() {
        var direct = EventSequencer.direct();
        List<String> seen = new ArrayList<>();

        direct.submit("any", () -> seen.add("ran"));

        assertThat(seen).containsExactly("ran");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
