package com.discord.gateway.integration;

import com.discord.gateway.command.BotCommandPersistenceService;
import com.discord.gateway.domain.CommandEventType;
import com.discord.gateway.domain.CommandPrefix;
import com.discord.gateway.model.BotCommandPayload;
import com.discord.gateway.repository.BotCommandLogRepository;
import com.discord.gateway.repository.BotCommandRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BotCommandSyncIT {

    @MockitoBean JDA jda;
    @MockitoBean @SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate;

    @Autowired BotCommandPersistenceService persistenceService;
    @Autowired BotCommandRepository commandRepository;
    @Autowired BotCommandLogRepository logRepository;

    @Test
    void upsert_newCommand_persistsInDb() {
        var payload = new BotCommandPayload("guild-it-1", "SLASH",
                "search", "Busca um item", List.of(), false);

        var command = persistenceService.upsert(payload);

        assertThat(command.getId()).isNotNull();
        assertThat(command.getName()).isEqualTo("search");
        assertThat(command.getGuildId()).isEqualTo("guild-it-1");
        assertThat(command.getPrefix()).isEqualTo(CommandPrefix.SLASH);
        assertThat(command.getVersion()).isEqualTo(1);
        assertThat(command.isDeleted()).isFalse();
    }

    @Test
    void upsert_existingCommand_incrementsVersion() {
        var payload = new BotCommandPayload("guild-it-2", "SLASH",
                "help", "Ajuda", List.of(), false);

        var first  = persistenceService.upsert(payload);
        var updated = new BotCommandPayload("guild-it-2", "SLASH",
                "help", "Ajuda v2", List.of(), false);
        var second = persistenceService.upsert(updated);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(second.getDescription()).isEqualTo("Ajuda v2");
    }

    @Test
    void saveResult_persistsLogEntry() {
        var payload = new BotCommandPayload("guild-it-3", "SLASH",
                "ping", "Pinga", List.of(), false);
        var command = persistenceService.upsert(payload);

        persistenceService.saveResult(command, CommandEventType.REGISTERED,
                "discord-cmd-xyz", true, null);

        var logs = logRepository.findAll().stream()
                .filter(l -> l.getId() != null)
                .toList();
        assertThat(logs).anyMatch(l -> true);
        assertThat(command.getDiscordCmdId()).isEqualTo("discord-cmd-xyz");
    }
}
