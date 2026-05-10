package com.discord.gateway.repository;

import com.discord.gateway.domain.GuildConfig;
import com.discord.gateway.domain.GuildParam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GuildConfigRepository extends JpaRepository<GuildConfig, Long> {
    Optional<GuildConfig> findByGuildIdAndParam(String guildId, GuildParam param);
}
