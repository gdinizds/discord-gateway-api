CREATE TABLE gateway.guild_config (
    id          BIGSERIAL PRIMARY KEY,
    guild_id    VARCHAR(20)             NOT NULL,
    param       gateway.guild_param_enum NOT NULL,
    value       TEXT                    NOT NULL,
    created_at  TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    UNIQUE (guild_id, param)
);

CREATE INDEX idx_guild_config_guild_id ON gateway.guild_config (guild_id);
