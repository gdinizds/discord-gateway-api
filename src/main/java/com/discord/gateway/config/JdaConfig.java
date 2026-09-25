package com.discord.gateway.config;

import com.discord.gateway.listener.DiscordEventListener;
import com.discord.gateway.startup.StartupReconciliationService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Configuration
public class JdaConfig {

    @Value("${discord.bot.token}")
    private String token;

    @Bean
    public JDA jda(DiscordEventListener listener, StartupReconciliationService reconciliation) throws InterruptedException {
        var httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .build();

        return JDABuilder.createDefault(token)
                .enableIntents(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MODERATION)
                .setHttpClient(httpClient)
                .setEventPool(Executors.newVirtualThreadPerTaskExecutor())
                .setCallbackPool(Executors.newVirtualThreadPerTaskExecutor())
                .addEventListeners(listener, reconciliation)
                .build()
                .awaitReady();
    }
}
