package com.discord.gateway.audit;

import com.discord.gateway.domain.GuildEventLog;
import com.discord.gateway.domain.GuildRegistry;
import com.discord.gateway.repository.GuildEventLogRepository;
import com.discord.gateway.repository.GuildRegistryRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class GuildLifecycleServiceTest {

    @Mock private GuildRegistryRepository guildRegistryRepository;
    @Mock private GuildEventLogRepository guildEventLogRepository;
    @Mock private ObjectMapper objectMapper;

    private MeterRegistry meterRegistry;
    private GuildLifecycleService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        CircuitBreaker cb = CircuitBreaker.ofDefaults("test-db");
        service = new GuildLifecycleService(
                guildRegistryRepository, guildEventLogRepository, 
                cb, objectMapper, meterRegistry);
    }

    @Test
    void shouldRegisterNewGuildOnJoin() throws Exception {
        Guild guild = mock(Guild.class);
        Member selfMember = mock(Member.class);

        when(guild.getId()).thenReturn("g1");
        when(guild.getName()).thenReturn("Guild One");
        when(guild.getMemberCount()).thenReturn(50);
        when(guild.getSelfMember()).thenReturn(selfMember);
        when(selfMember.getPermissions()).thenReturn(EnumSet.noneOf(Permission.class));
        
        when(guildRegistryRepository.findById("g1")).thenReturn(Optional.empty());
        when(objectMapper.writeValueAsString(any())).thenReturn("{\\\"test\\\":true}");

        service.onJoin(guild);

        ArgumentCaptor<GuildRegistry> registryCaptor = ArgumentCaptor.forClass(GuildRegistry.class);
        verify(guildRegistryRepository).save(registryCaptor.capture());
        
        GuildRegistry saved = registryCaptor.getValue();
        assertThat(saved.getGuildId()).isEqualTo("g1");
        assertThat(saved.getGuildName()).isEqualTo("Guild One");

        ArgumentCaptor<GuildEventLog> logCaptor = ArgumentCaptor.forClass(GuildEventLog.class);
        verify(guildEventLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getEventType()).isEqualTo("JOINED");
    }

    @Test
    void shouldUpdateExistingGuildOnJoin() throws Exception {
        Guild guild = mock(Guild.class);
        Member selfMember = mock(Member.class);

        when(guild.getId()).thenReturn("g2");
        when(guild.getName()).thenReturn("Guild Two");
        when(guild.getMemberCount()).thenReturn(100);
        when(guild.getSelfMember()).thenReturn(selfMember);
        when(selfMember.getPermissions()).thenReturn(EnumSet.noneOf(Permission.class));

        GuildRegistry existing = mock(GuildRegistry.class);
        when(guildRegistryRepository.findById("g2")).thenReturn(Optional.of(existing));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.onJoin(guild);

        verify(existing).markActive(eq("Guild Two"), eq(100), anyLong());
        verify(guildRegistryRepository, never()).save(any(GuildRegistry.class));
        verify(guildEventLogRepository).save(any(GuildEventLog.class));
    }

    @Test
    void shouldMarkGuildAsLeft() throws Exception {
        GuildRegistry existing = mock(GuildRegistry.class);
        when(guildRegistryRepository.findById("g3")).thenReturn(Optional.of(existing));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.onLeave("g3", "Guild Three");

        verify(existing).markLeft();
        ArgumentCaptor<GuildEventLog> logCaptor = ArgumentCaptor.forClass(GuildEventLog.class);
        verify(guildEventLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getEventType()).isEqualTo("LEFT");
    }
}
