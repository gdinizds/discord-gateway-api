package com.discord.gateway;

import com.discord.gateway.config.GraalVmHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ImportRuntimeHints(GraalVmHints.class)
public class DiscordEventGatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(DiscordEventGatewayApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DiscordEventGatewayApplication.class, args);
        log.info("discord-event-gateway started — sole point of contact with the Discord API");
    }
}
