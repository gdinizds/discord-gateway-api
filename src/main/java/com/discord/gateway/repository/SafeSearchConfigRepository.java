package com.discord.gateway.repository;

import com.discord.gateway.domain.SafeSearchConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SafeSearchConfigRepository extends JpaRepository<SafeSearchConfig, Long> {
    Optional<SafeSearchConfig> findByGuildIdAndChannelId(String guildId, String channelId);
}
