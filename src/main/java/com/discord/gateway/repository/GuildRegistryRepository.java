package com.discord.gateway.repository;

import com.discord.gateway.domain.GuildRegistry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildRegistryRepository extends JpaRepository<GuildRegistry, String> {
}
