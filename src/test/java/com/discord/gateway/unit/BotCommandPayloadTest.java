package com.discord.gateway.unit;

import com.discord.gateway.model.BotCommandPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotCommandPayloadTest {

    @Test
    void validPayload_isValid() {
        var p = new BotCommandPayload("guild-1", "SLASH", "ping", "Pinga o bot", List.of(), false);
        assertThat(p.isValid()).isTrue();
    }

    @Test
    void missingPrefix_isInvalid() {
        var p = new BotCommandPayload("guild-1", null, "ping", "Pinga", List.of(), false);
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void missingName_isInvalid() {
        var p = new BotCommandPayload("guild-1", "SLASH", null, "Pinga", List.of(), false);
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void missingDescription_isInvalid() {
        var p = new BotCommandPayload("guild-1", "SLASH", "ping", null, List.of(), false);
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void nullGuildId_resolvesToGlobal() {
        var p = new BotCommandPayload(null, "SLASH", "ping", "Pinga", List.of(), false);
        assertThat(p.resolvedGuildId()).isEqualTo("GLOBAL");
    }

    @Test
    void blankGuildId_resolvesToGlobal() {
        var p = new BotCommandPayload("  ", "SLASH", "ping", "Pinga", List.of(), false);
        assertThat(p.resolvedGuildId()).isEqualTo("GLOBAL");
    }

    @Test
    void presentGuildId_resolvesAsIs() {
        var p = new BotCommandPayload("123456789", "SLASH", "ping", "Pinga", List.of(), false);
        assertThat(p.resolvedGuildId()).isEqualTo("123456789");
    }

    @Test
    void ephemeral_defaultsToFalseWithLegacyConstructor() {
        var p = new BotCommandPayload("guild-1", "SLASH", "ping", "Pinga", List.of(), false);
        assertThat(p.isEphemeral()).isFalse();
    }

    @Test
    void ephemeral_absentInJson_isFalse() throws Exception {
        var json = "{\"prefix\":\"SLASH\",\"name\":\"ia\",\"description\":\"IA\",\"is_deleted\":false}";
        var p = JsonMapper.builder().build().readValue(json, BotCommandPayload.class);
        assertThat(p.isEphemeral()).isFalse();
    }

    @Test
    void ephemeral_trueInJson_isTrue() throws Exception {
        var json = "{\"prefix\":\"SLASH\",\"name\":\"ia-memoria\",\"description\":\"Memorias\",\"is_deleted\":false,\"ephemeral\":true}";
        var p = JsonMapper.builder().build().readValue(json, BotCommandPayload.class);
        assertThat(p.isEphemeral()).isTrue();
    }
}
