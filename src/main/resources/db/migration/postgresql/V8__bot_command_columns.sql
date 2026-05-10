-- Add DOT prefix and REGISTERED event type to existing ENUMs
ALTER TYPE gateway.command_prefix_enum     ADD VALUE IF NOT EXISTS 'DOT';
ALTER TYPE gateway.command_event_type_enum ADD VALUE IF NOT EXISTS 'REGISTERED';

-- Add missing columns to bot_command
ALTER TABLE gateway.bot_command
    ADD COLUMN IF NOT EXISTS bot_id     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS parameters JSONB   NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS version    INT     NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;

-- Add missing columns to bot_command_log
ALTER TABLE gateway.bot_command_log
    ADD COLUMN IF NOT EXISTS bot_id          VARCHAR(20),
    ADD COLUMN IF NOT EXISTS discord_success BOOLEAN,
    ADD COLUMN IF NOT EXISTS discord_error   TEXT;
