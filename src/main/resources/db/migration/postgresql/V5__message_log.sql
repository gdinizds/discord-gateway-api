CREATE TABLE gateway.message_log (
    id                  BIGSERIAL,
    correlation_id      UUID                          NOT NULL,
    version             INT                           NOT NULL DEFAULT 1,
    direction           gateway.message_direction_enum NOT NULL,
    event_type          VARCHAR(64),
    discord_message_id  VARCHAR(20),
    discord_channel_id  VARCHAR(20),
    guild_id            VARCHAR(20)                   NOT NULL,
    discord_user_id     VARCHAR(20),
    payload             JSONB                         NOT NULL,
    recorded_at         TIMESTAMPTZ                   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, recorded_at, guild_id)
) PARTITION BY RANGE (recorded_at);

CREATE INDEX idx_message_log_correlation
    ON gateway.message_log (correlation_id, version);

CREATE INDEX idx_message_log_discord_message_id
    ON gateway.message_log (discord_message_id)
    WHERE discord_message_id IS NOT NULL;
