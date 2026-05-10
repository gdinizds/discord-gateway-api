package com.discord.gateway.unit;

import com.discord.gateway.model.BotCommandPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotCommandPayloadTest {

    @Test
    void validPayload_isValid() {
        var p = new BotCommandPayload("bot-1", "guild-1", "SLASH", "ping", "Pinga o bot", List.of(), false);
        assertThat(p.isValid()).isTrue();
    }

    @Test
    void missingBotId_isInvalid() {
        var p = new BotCommandPayload(null, "guild-1", "SLASH", "ping", "Pinga", List.of(), false);
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void missingName_isInvalid() {
        var p = new BotCommandPayload("bot-1", "guild-1", "SLASH", null, "Pinga", List.of(), false);
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void missingDescription_isInvalid() {
        var p = new BotCommandPayload("bot-1", "guild-1", "SLASH", "ping", null, List.of(), false);
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void nullGuildId_resolvesToGlobal() {
        var p = new BotCommandPayload("bot-1", null, "SLASH", "ping", "Pinga", List.of(), false);
        assertThat(p.resolvedGuildId()).isEqualTo("GLOBAL");
    }

    @Test
    void blankGuildId_resolvesToGlobal() {
        var p = new BotCommandPayload("bot-1", "  ", "SLASH", "ping", "Pinga", List.of(), false);
        assertThat(p.resolvedGuildId()).isEqualTo("GLOBAL");
    }

    @Test
    void presentGuildId_resolvesAsIs() {
        var p = new BotCommandPayload("bot-1", "123456789", "SLASH", "ping", "Pinga", List.of(), false);
        assertThat(p.resolvedGuildId()).isEqualTo("123456789");
    }
}
