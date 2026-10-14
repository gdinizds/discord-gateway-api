CREATE TABLE gateway.safesearch_config (
    id          BIGSERIAL PRIMARY KEY,
    guild_id    VARCHAR(20) NOT NULL,
    channel_id  VARCHAR(20) NOT NULL DEFAULT '',
    level       INT NOT NULL CHECK (level IN (0, 1, 2)),
    updated_by  VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (guild_id, channel_id)
);

CREATE INDEX idx_safesearch_config_guild_id ON gateway.safesearch_config (guild_id);
CREATE INDEX idx_safesearch_config_channel_id ON gateway.safesearch_config (channel_id);

CREATE TABLE gateway.config_audit_log (
    id          BIGSERIAL PRIMARY KEY,
    guild_id    VARCHAR(20) NOT NULL,
    channel_id  VARCHAR(20),
    user_id     VARCHAR(20) NOT NULL,
    setting_key VARCHAR(64) NOT NULL,
    old_value   VARCHAR(255),
    new_value   VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_config_audit_guild_id ON gateway.config_audit_log (guild_id);
CREATE INDEX idx_config_audit_setting_key ON gateway.config_audit_log (setting_key);
CREATE INDEX idx_config_audit_created_at ON gateway.config_audit_log (created_at DESC);
