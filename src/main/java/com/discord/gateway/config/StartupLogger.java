package com.discord.gateway.config;

import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !integration-test")
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final JDA jda;

    public StartupLogger(JDA jda) {
        this.jda = jda;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info(
                "discord-event-gateway ONLINE — único ponto de contato com a API Discord " +
                "[status={}, guilds={}]",
                jda.getStatus().name(),
                jda.getGuilds().size()
        );
    }
}
