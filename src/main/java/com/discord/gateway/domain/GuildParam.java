package com.discord.gateway.domain;

public enum GuildParam {

    MAX_ATTACHMENT_SIZE_BYTES("Tamanho máximo de anexo em bytes (sobrescreve o global)") {
        @Override
        public String validate(String value) {
            try { if (Long.parseLong(value) > 0) return null; } catch (NumberFormatException ignored) {}
            return "deve ser um número inteiro positivo";
        }
    },

    ATTACHMENT_RELAY_ENABLED("Se o relay de anexos está habilitado para esta guilda (true/false)") {
        @Override
        public String validate(String value) {
            if ("true".equals(value) || "false".equals(value)) return null;
            return "deve ser 'true' ou 'false'";
        }
    },

    ALLOWED_CHANNELS("IDs de canal separados por vírgula onde o gateway processa eventos, ou '*' para todos") {
        @Override
        public String validate(String value) {
            if (value == null || value.isBlank()) return "não pode ser vazio";
            if ("*".equals(value.strip())) return null;
            for (String id : value.split(",")) {
                String t = id.strip();
                if (!t.matches("\\d{17,20}")) return "'" + t + "' não é um snowflake de canal válido";
            }
            return null;
        }
    };

    private final String description;

    GuildParam(String description) { this.description = description; }

    public String getDescription() { return description; }

    /** @return null se válido, descrição do erro caso contrário */
    public abstract String validate(String value);
}
