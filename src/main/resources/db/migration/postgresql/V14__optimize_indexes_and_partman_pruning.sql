ALTER TABLE gateway.guild_registry SET (fillfactor = 85);

DROP INDEX IF EXISTS gateway.idx_message_log_discord_message_id;
CREATE INDEX IF NOT EXISTS idx_message_log_discord_msg_covering
    ON gateway.message_log (discord_message_id)
    INCLUDE (correlation_id, version)
    WHERE discord_message_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bot_command_ephemeral_active
    ON gateway.bot_command (prefix)
    WHERE is_deleted = false AND ephemeral = true;

DROP INDEX IF EXISTS gateway.idx_config_audit_guild_id;
DROP INDEX IF EXISTS gateway.idx_config_audit_created_at;
CREATE INDEX IF NOT EXISTS idx_config_audit_guild_created
    ON gateway.config_audit_log (guild_id, created_at DESC);
