CREATE TABLE gateway.bot_command (
    id                  BIGSERIAL PRIMARY KEY,
    command_name        VARCHAR(64)               NOT NULL,
    guild_id            VARCHAR(20),
    discord_command_id  VARCHAR(20),
    description         VARCHAR(256),
    prefix              gateway.command_prefix_enum NOT NULL DEFAULT 'SLASH',
    enabled             BOOLEAN                   NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    UNIQUE (command_name, guild_id)
);

CREATE INDEX idx_bot_command_guild_id ON gateway.bot_command (guild_id);
