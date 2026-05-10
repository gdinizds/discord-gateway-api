package com.discord.gateway.unit;

import com.discord.gateway.config.JdaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class TokenIsolationTest {

    @Test
    void jdaConfigInjectsTokenViaValueAnnotation() throws NoSuchFieldException {
        Field tokenField = JdaConfig.class.getDeclaredField("token");
        assertThat(tokenField.isAnnotationPresent(Value.class)).isTrue();
        Value annotation = tokenField.getAnnotation(Value.class);
        assertThat(annotation.value()).isEqualTo("${discord.bot.token}");
    }

    @Test
    void jdaConfigClassContainsNoHardcodedToken() throws Exception {
        // Verify that the JdaConfig source doesn't contain a literal token string.
        // This is a structural guard — the token must always come from environment/config.
        Field tokenField = JdaConfig.class.getDeclaredField("token");
        tokenField.setAccessible(true);
        // Field value is null when instantiated without Spring injection — not a hardcoded string
        JdaConfig config = new JdaConfig();
        assertThat((String) tokenField.get(config)).isNull();
    }
}
