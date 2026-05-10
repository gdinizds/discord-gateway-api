package com.discord.gateway.repository;

import com.discord.gateway.domain.GuildEventLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildEventLogRepository extends JpaRepository<GuildEventLog, Long> {
}
