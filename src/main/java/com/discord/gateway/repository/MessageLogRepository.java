package com.discord.gateway.repository;

import com.discord.gateway.domain.MessageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageLogRepository extends JpaRepository<MessageLog, Long> {

    @Query(value = """
            SELECT correlation_id, MAX(version) AS max_version
            FROM gateway.message_log
            WHERE discord_message_id = :messageId
            GROUP BY correlation_id
            LIMIT 1
            """, nativeQuery = true)
    List<Object[]> findCorrelationByDiscordMessageId(@Param("messageId") String messageId);
}
