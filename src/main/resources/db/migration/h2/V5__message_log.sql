-- H2 does not support table partitioning; flat table is functionally equivalent
CREATE TABLE GATEWAY.message_log (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    correlation_id      UUID         NOT NULL,
    version             INT          NOT NULL DEFAULT 1,
    direction           VARCHAR(8)   NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    event_type          VARCHAR(64),
    discord_message_id  VARCHAR(20),
    discord_channel_id  VARCHAR(20),
    guild_id            VARCHAR(20)  NOT NULL,
    discord_user_id     VARCHAR(20),
    payload             CLOB         NOT NULL,
    recorded_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_message_log_correlation
    ON GATEWAY.message_log (correlation_id, version);

CREATE INDEX idx_message_log_discord_message_id
    ON GATEWAY.message_log (discord_message_id)
    WHERE discord_message_id IS NOT NULL;
