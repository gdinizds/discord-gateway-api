-- Slash commands registered with "ephemeral": true are deferred ephemerally by the gateway
ALTER TABLE GATEWAY.bot_command
    ADD COLUMN IF NOT EXISTS ephemeral BOOLEAN NOT NULL DEFAULT false;
