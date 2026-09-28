-- Slash commands registered with "ephemeral": true are deferred ephemerally by the gateway
ALTER TABLE gateway.bot_command
    ADD COLUMN IF NOT EXISTS ephemeral BOOLEAN NOT NULL DEFAULT false;
