CREATE SCHEMA IF NOT EXISTS gateway;

CREATE TYPE gateway.guild_status_enum AS ENUM ('ACTIVE', 'LEFT', 'BANNED');

CREATE TYPE gateway.guild_event_type_enum AS ENUM (
    'JOINED', 'LEFT', 'NAME_CHANGED', 'PERMISSIONS_CHANGED'
);

CREATE TYPE gateway.message_direction_enum AS ENUM ('INBOUND', 'OUTBOUND');

CREATE TYPE gateway.guild_param_enum AS ENUM (
    'MAX_ATTACHMENT_SIZE_BYTES',
    'ATTACHMENT_RELAY_ENABLED',
    'ALLOWED_CHANNELS'
);

CREATE TYPE gateway.command_prefix_enum AS ENUM ('SLASH', 'TEXT');

CREATE TYPE gateway.command_event_type_enum AS ENUM (
    'CREATED', 'UPDATED', 'DELETED', 'EXECUTED', 'FAILED'
);
