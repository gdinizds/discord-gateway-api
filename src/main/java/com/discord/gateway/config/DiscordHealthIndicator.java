package com.discord.gateway.config;

import net.dv8tion.jda.api.JDA;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("discord-websocket")
public class DiscordHealthIndicator implements HealthIndicator {

    private final JDA jda;

    public DiscordHealthIndicator(JDA jda) {
        this.jda = jda;
    }

    @Override
    public Health health() {
        JDA.Status status = jda.getStatus();
        if (status == JDA.Status.CONNECTED) {
            return Health.up()
                    .withDetail("status", status.name())
                    .withDetail("guilds", jda.getGuilds().size())
                    .build();
        }
        return Health.down()
                .withDetail("status", status.name())
                .build();
    }
}
