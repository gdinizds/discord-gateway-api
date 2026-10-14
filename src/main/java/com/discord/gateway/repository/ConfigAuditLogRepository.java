package com.discord.gateway.repository;

import com.discord.gateway.domain.ConfigAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfigAuditLogRepository extends JpaRepository<ConfigAuditLog, Long> {
}
