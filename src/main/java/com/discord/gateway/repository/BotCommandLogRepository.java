package com.discord.gateway.repository;

import com.discord.gateway.domain.BotCommandLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotCommandLogRepository extends JpaRepository<BotCommandLog, Long> {}
