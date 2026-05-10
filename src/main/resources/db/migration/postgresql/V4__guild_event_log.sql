CREATE TABLE gateway.guild_event_log (
    id          BIGSERIAL                     PRIMARY KEY,
    guild_id    VARCHAR(20)                   NOT NULL,
    event_type  gateway.guild_event_type_enum NOT NULL,
    payload     JSONB,
    recorded_at TIMESTAMPTZ                   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_guild_event_log_guild_id ON gateway.guild_event_log (guild_id, recorded_at DESC);
