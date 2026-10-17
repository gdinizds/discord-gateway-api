package com.discord.gateway.config;

import net.dv8tion.jda.api.JDA;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("discord-websocket")
public class DiscordHealthIndicator implements HealthIndicator {

    private static final long CACHE_TTL_MS = 5_000;

    private final JDA jda;
    private volatile int cachedGuildCount = 0;
    private volatile long lastGuildCountCheck = 0;

    public DiscordHealthIndicator(JDA jda) {
        this.jda = jda;
    }

    @Override
    public Health health() {
        JDA.Status status = jda.getStatus();
        if (status == JDA.Status.CONNECTED) {
            return Health.up()
                    .withDetail("status", status.name())
                    .withDetail("guilds", getCachedGuildCount())
                    .build();
        }
        return Health.down()
                .withDetail("status", status.name())
                .build();
    }

    private int getCachedGuildCount() {
        long now = System.currentTimeMillis();
        if (now - lastGuildCountCheck > CACHE_TTL_MS) {
            cachedGuildCount = jda.getGuilds().size();
            lastGuildCountCheck = now;
        }
        return cachedGuildCount;
    }
}
