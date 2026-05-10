CREATE TABLE GATEWAY.guild_registry (
    guild_id        VARCHAR(20)  PRIMARY KEY,
    guild_name      VARCHAR(100),
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LEFT', 'BANNED')),
    member_count    INTEGER,
    bot_permissions BIGINT,
    joined_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at         TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
