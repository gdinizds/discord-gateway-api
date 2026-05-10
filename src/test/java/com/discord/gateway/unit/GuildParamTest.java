package com.discord.gateway.unit;

import com.discord.gateway.domain.GuildParam;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuildParamTest {

    @Test
    void maxSizeBytes_acceptsPositiveLong() {
        assertThat(GuildParam.MAX_ATTACHMENT_SIZE_BYTES.validate("1048576")).isNull();
    }

    @Test
    void maxSizeBytes_rejectsZero() {
        assertThat(GuildParam.MAX_ATTACHMENT_SIZE_BYTES.validate("0")).isNotNull();
    }

    @Test
    void maxSizeBytes_rejectsNegative() {
        assertThat(GuildParam.MAX_ATTACHMENT_SIZE_BYTES.validate("-1")).isNotNull();
    }

    @Test
    void maxSizeBytes_rejectsNonNumeric() {
        assertThat(GuildParam.MAX_ATTACHMENT_SIZE_BYTES.validate("abc")).isNotNull();
    }

    @Test
    void attachmentRelayEnabled_acceptsTrue() {
        assertThat(GuildParam.ATTACHMENT_RELAY_ENABLED.validate("true")).isNull();
    }

    @Test
    void attachmentRelayEnabled_acceptsFalse() {
        assertThat(GuildParam.ATTACHMENT_RELAY_ENABLED.validate("false")).isNull();
    }

    @Test
    void attachmentRelayEnabled_rejectsOther() {
        assertThat(GuildParam.ATTACHMENT_RELAY_ENABLED.validate("yes")).isNotNull();
    }

    @Test
    void allowedChannels_acceptsWildcard() {
        assertThat(GuildParam.ALLOWED_CHANNELS.validate("*")).isNull();
    }

    @Test
    void allowedChannels_acceptsSingleSnowflake() {
        assertThat(GuildParam.ALLOWED_CHANNELS.validate("123456789012345678")).isNull();
    }

    @Test
    void allowedChannels_acceptsCommaSeparatedSnowflakes() {
        assertThat(GuildParam.ALLOWED_CHANNELS.validate("123456789012345678,987654321098765432")).isNull();
    }

    @Test
    void allowedChannels_rejectsNonSnowflake() {
        assertThat(GuildParam.ALLOWED_CHANNELS.validate("not-a-channel")).isNotNull();
    }

    @Test
    void allowedChannels_rejectsBlank() {
        assertThat(GuildParam.ALLOWED_CHANNELS.validate("")).isNotNull();
    }

    @Test
    void allParamsHaveNonBlankDescription() {
        for (GuildParam p : GuildParam.values()) {
            assertThat(p.getDescription()).isNotBlank();
        }
    }
}
