package com.discord.gateway.unit;

import com.discord.gateway.command.EphemeralCommandRegistry;
import com.discord.gateway.repository.BotCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EphemeralCommandRegistryTest {

    private EphemeralCommandRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EphemeralCommandRegistry(mock(BotCommandRepository.class));
    }

    @Test
    void unknownCommandIsNotEphemeral() {
        assertThat(registry.isEphemeral("guild-1", "ia")).isFalse();
    }

    @Test
    void guildCommandIsEphemeralOnlyInItsGuild() {
        registry.apply("guild-1", "ia-memoria", true, false);
        assertThat(registry.isEphemeral("guild-1", "ia-memoria")).isTrue();
        assertThat(registry.isEphemeral("guild-2", "ia-memoria")).isFalse();
    }

    @Test
    void globalCommandAppliesToEveryGuild() {
        registry.apply("GLOBAL", "ia-memoria", true, false);
        assertThat(registry.isEphemeral("guild-1", "ia-memoria")).isTrue();
        assertThat(registry.isEphemeral("guild-2", "ia-memoria")).isTrue();
    }

    @Test
    void nullGuildIsTreatedAsGlobal() {
        registry.apply(null, "ia-memoria", true, false);
        assertThat(registry.isEphemeral("guild-1", "ia-memoria")).isTrue();
    }

    @Test
    void reRegisteringWithoutFlagMakesItPublicAgain() {
        registry.apply("guild-1", "ia-memoria", true, false);
        registry.apply("guild-1", "ia-memoria", false, false);
        assertThat(registry.isEphemeral("guild-1", "ia-memoria")).isFalse();
    }

    @Test
    void deletingCommandRemovesIt() {
        registry.apply("guild-1", "ia-memoria", true, false);
        registry.apply("guild-1", "ia-memoria", true, true);
        assertThat(registry.isEphemeral("guild-1", "ia-memoria")).isFalse();
    }
}
