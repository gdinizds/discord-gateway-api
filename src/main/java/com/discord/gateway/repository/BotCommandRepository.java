package com.discord.gateway.repository;

import com.discord.gateway.domain.BotCommand;
import com.discord.gateway.domain.CommandPrefix;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BotCommandRepository extends JpaRepository<BotCommand, Long> {
    Optional<BotCommand> findByGuildIdAndPrefixAndName(
            String guildId, CommandPrefix prefix, String name);
}
