CREATE TABLE GATEWAY.bot_command_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id  BIGINT       REFERENCES GATEWAY.bot_command(id),
    event_type  VARCHAR(16)  NOT NULL CHECK (event_type IN ('CREATED', 'UPDATED', 'DELETED', 'EXECUTED', 'FAILED')),
    guild_id    VARCHAR(20),
    user_id     VARCHAR(20),
    payload     CLOB,
    recorded_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_bot_command_log_command_id
    ON GATEWAY.bot_command_log (command_id, recorded_at DESC);
