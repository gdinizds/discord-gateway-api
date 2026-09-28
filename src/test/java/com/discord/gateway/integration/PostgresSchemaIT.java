package com.discord.gateway.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@RequiresDocker
class PostgresSchemaIT {

    @Container
    static PostgreSQLContainer postgres = TestPostgresImage.newContainer("gateway_schema")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("gateway")
                .locations("classpath:db/migration/postgresql")
                .load()
                .migrate();
    }

    @Test
    void guildEventLogAcceptsEveryEventTypeTheListenerWrites() {
        assertThatCode(() -> execute("""
                INSERT INTO gateway.guild_event_log (guild_id, event_type, payload) VALUES
                ('1', 'JOINED', '{}'),
                ('1', 'LEFT', '{}'),
                ('1', 'NAME_CHANGED', '{}'),
                ('1', 'BOOST_TIER_CHANGED', '{}')
                """)).doesNotThrowAnyException();
    }

    @Test
    void sameCommandNameIsAllowedForDifferentPrefixes() {
        assertThatCode(() -> execute("""
                INSERT INTO gateway.bot_command (command_name, guild_id, prefix) VALUES
                ('ia', 'guild-schema', 'SLASH'),
                ('ia', 'guild-schema', 'DOT')
                """)).doesNotThrowAnyException();
    }

    @Test
    void sameCommandNameAndPrefixIsRejected() throws SQLException {
        execute("INSERT INTO gateway.bot_command (command_name, guild_id, prefix) VALUES ('dup', 'guild-schema', 'SLASH')");

        assertThatThrownBy(() -> execute(
                "INSERT INTO gateway.bot_command (command_name, guild_id, prefix) VALUES ('dup', 'guild-schema', 'SLASH')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_bot_command_scope");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
