package com.discord.gateway.config;

import net.dv8tion.jda.api.JDA;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

import static org.mockito.Mockito.when;

@TestConfiguration
public class TestDiscordConfig {

    @Bean
    @Primary
    public JDA mockJda() {
        JDA mock = Mockito.mock(JDA.class);
        when(mock.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(mock.getGuilds()).thenReturn(List.of());
        return mock;
    }
}
