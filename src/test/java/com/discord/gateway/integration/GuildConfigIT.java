package com.discord.gateway.integration;

import com.discord.gateway.audit.GuildConfigService;
import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.repository.GuildConfigRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GuildConfigIT {

    @MockitoBean JDA jda;
    @MockitoBean @SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate;

    @Autowired GuildConfigService guildConfigService;
    @Autowired GuildConfigRepository guildConfigRepository;

    @Test
    void upsert_newParam_persistsInDb() {
        var result = guildConfigService.upsert(
                "guild-cfg-new", GuildParam.ALLOWED_CHANNELS, "123456789012345678");

        assertThat(result.success()).isTrue();
        var saved = guildConfigRepository.findByGuildIdAndParam(
                "guild-cfg-new", GuildParam.ALLOWED_CHANNELS);
        assertThat(saved).isPresent();
        assertThat(saved.get().getValue()).isEqualTo("123456789012345678");
    }

    @Test
    void upsert_existingParam_updatesValueInDb() {
        guildConfigService.upsert("guild-cfg-upd", GuildParam.ATTACHMENT_RELAY_ENABLED, "true");

        var result = guildConfigService.upsert(
                "guild-cfg-upd", GuildParam.ATTACHMENT_RELAY_ENABLED, "false");

        assertThat(result.success()).isTrue();
        var config = guildConfigRepository.findByGuildIdAndParam(
                "guild-cfg-upd", GuildParam.ATTACHMENT_RELAY_ENABLED);
        assertThat(config).isPresent();
        assertThat(config.get().getValue()).isEqualTo("false");
    }
}
