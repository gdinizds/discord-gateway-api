package com.discord.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DiscordEventGatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(DiscordEventGatewayApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DiscordEventGatewayApplication.class, args);
        log.info("discord-event-gateway started — único ponto de contato com a API Discord");
    }
}