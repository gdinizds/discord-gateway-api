CREATE TABLE GATEWAY.bot_command (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_name        VARCHAR(64)  NOT NULL,
    guild_id            VARCHAR(20),
    discord_command_id  VARCHAR(20),
    description         VARCHAR(256),
    prefix              VARCHAR(8)   NOT NULL DEFAULT 'SLASH' CHECK (prefix IN ('SLASH', 'TEXT')),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (command_name, guild_id)
);

CREATE INDEX idx_bot_command_guild_id ON GATEWAY.bot_command (guild_id);
