CREATE TABLE GATEWAY.guild_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id    VARCHAR(20)  NOT NULL,
    param       VARCHAR(32)  NOT NULL CHECK (param IN ('MAX_ATTACHMENT_SIZE_BYTES', 'ATTACHMENT_RELAY_ENABLED', 'ALLOWED_CHANNELS')),
    value       TEXT         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (guild_id, param)
);

CREATE INDEX idx_guild_config_guild_id ON GATEWAY.guild_config (guild_id);
