ALTER TYPE gateway.guild_event_type_enum ADD VALUE IF NOT EXISTS 'BOOST_TIER_CHANGED';

ALTER TABLE gateway.bot_command DROP CONSTRAINT IF EXISTS bot_command_command_name_guild_id_key;
ALTER TABLE gateway.bot_command
    ADD CONSTRAINT uq_bot_command_scope UNIQUE (command_name, guild_id, prefix);
