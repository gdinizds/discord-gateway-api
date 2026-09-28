package com.discord.gateway.unit;

import com.discord.gateway.listener.DotCommandParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DotCommandParserTest {

    @Test
    void parsesNameInLowerCase() {
        assertThat(DotCommandParser.parse(".BAIXAR https://x.com/a").name()).isEqualTo("baixar");
    }

    @Test
    void keepsWhitespaceSplitArgsForExistingConsumers() {
        var parsed = DotCommandParser.parse(".baixar https://x.com/a   720p");
        assertThat(parsed.args()).containsExactly("https://x.com/a", "720p");
    }

    @Test
    void contentPreservesLineBreaksAndCodeBlocks() {
        var parsed = DotCommandParser.parse(".ia explique:\n```sql\nSELECT 1\n  FROM dual;\n```");
        assertThat(parsed.name()).isEqualTo("ia");
        assertThat(parsed.content()).isEqualTo("explique:\n```sql\nSELECT 1\n  FROM dual;\n```");
    }

    @Test
    void commandNameFollowedByNewlineStillParses() {
        var parsed = DotCommandParser.parse(".ia\nprimeira linha\nsegunda linha");
        assertThat(parsed.name()).isEqualTo("ia");
        assertThat(parsed.content()).isEqualTo("primeira linha\nsegunda linha");
    }

    @Test
    void commandWithoutTextHasEmptyArgsAndContent() {
        var parsed = DotCommandParser.parse(".ia");
        assertThat(parsed.args()).isEmpty();
        assertThat(parsed.content()).isEmpty();
    }

    @Test
    void trailingWhitespaceOnlyIsTreatedAsEmpty() {
        var parsed = DotCommandParser.parse(".ia   \n  ");
        assertThat(parsed.args()).isEmpty();
        assertThat(parsed.content()).isEmpty();
    }
}
