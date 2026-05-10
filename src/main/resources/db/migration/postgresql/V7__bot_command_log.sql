CREATE TABLE gateway.bot_command_log (
    id          BIGSERIAL                        PRIMARY KEY,
    command_id  BIGINT REFERENCES gateway.bot_command(id),
    event_type  gateway.command_event_type_enum  NOT NULL,
    guild_id    VARCHAR(20),
    user_id     VARCHAR(20),
    payload     JSONB,
    recorded_at TIMESTAMPTZ                      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bot_command_log_command_id
    ON gateway.bot_command_log (command_id, recorded_at DESC);
