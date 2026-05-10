package com.discord.gateway.repository;

import com.discord.gateway.domain.BotCommand;
import com.discord.gateway.domain.CommandPrefix;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BotCommandRepository extends JpaRepository<BotCommand, Long> {
    Optional<BotCommand> findByGuildIdAndBotIdAndPrefixAndName(
            String guildId, String botId, CommandPrefix prefix, String name);
}
