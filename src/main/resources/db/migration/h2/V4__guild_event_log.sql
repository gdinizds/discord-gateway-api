CREATE TABLE GATEWAY.guild_event_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id    VARCHAR(20)  NOT NULL,
    event_type  VARCHAR(24)  NOT NULL CHECK (event_type IN ('JOINED', 'LEFT', 'NAME_CHANGED', 'PERMISSIONS_CHANGED')),
    payload     CLOB,
    recorded_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_guild_event_log_guild_id ON GATEWAY.guild_event_log (guild_id, recorded_at DESC);
