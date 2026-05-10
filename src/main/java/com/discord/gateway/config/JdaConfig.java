package com.discord.gateway.config;

import com.discord.gateway.listener.DiscordEventListener;
import com.discord.gateway.startup.StartupReconciliationService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JdaConfig {

    @Value("${discord.bot.token}")
    private String token;

    @Bean
    public JDA jda(DiscordEventListener listener, StartupReconciliationService reconciliation) throws InterruptedException {
        return JDABuilder.createDefault(token)
                .enableIntents(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MODERATION)
                .addEventListeners(listener, reconciliation)
                .build()
                .awaitReady();
    }
}
