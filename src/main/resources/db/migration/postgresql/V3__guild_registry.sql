CREATE TABLE gateway.guild_registry (
    guild_id        VARCHAR(20)              PRIMARY KEY,
    guild_name      VARCHAR(100),
    status          gateway.guild_status_enum NOT NULL DEFAULT 'ACTIVE',
    member_count    INTEGER,
    bot_permissions BIGINT,
    joined_at       TIMESTAMPTZ              NOT NULL DEFAULT NOW(),
    left_at         TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ              NOT NULL DEFAULT NOW()
);
