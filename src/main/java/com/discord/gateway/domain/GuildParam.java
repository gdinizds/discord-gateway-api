package com.discord.gateway.domain;

public enum GuildParam {

    MAX_ATTACHMENT_SIZE_BYTES("Maximum attachment size in bytes (overrides global default)") {
        @Override
        public String validate(String value) {
            try { if (Long.parseLong(value) > 0) return null; } catch (NumberFormatException ignored) {}
            return "must be a positive integer";
        }
    },

    ATTACHMENT_RELAY_ENABLED("Whether attachment relay is enabled for this guild (true/false)") {
        @Override
        public String validate(String value) {
            if ("true".equals(value) || "false".equals(value)) return null;
            return "must be 'true' or 'false'";
        }
    },

    ALLOWED_CHANNELS("Comma-separated channel IDs the gateway processes events from, or '*' for all") {
        @Override
        public String validate(String value) {
            if (value == null || value.isBlank()) return "cannot be blank";
            if ("*".equals(value.strip())) return null;
            for (String id : value.split(",")) {
                String t = id.strip();
                if (!t.matches("\\d{17,20}")) return "'" + t + "' is not a valid channel snowflake";
            }
            return null;
        }
    };

    private final String description;

    GuildParam(String description) { this.description = description; }

    public String getDescription() { return description; }

    public abstract String validate(String value);
}
