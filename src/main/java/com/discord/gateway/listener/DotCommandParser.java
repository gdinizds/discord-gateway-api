package com.discord.gateway.listener;

import java.util.Arrays;
import java.util.List;

public final class DotCommandParser {

    public record ParsedDotCommand(String name, List<String> args, String content) {}

    private DotCommandParser() {}

    public static ParsedDotCommand parse(String rawContent) {
        String withoutDot = rawContent.substring(1).strip();
        String[] parts = withoutDot.split("\\s+", 2);
        String name = parts[0].toLowerCase();
        String content = parts.length > 1 ? parts[1].strip() : "";
        List<String> args = content.isEmpty()
                ? List.of()
                : Arrays.asList(content.split("\\s+"));
        return new ParsedDotCommand(name, args, content);
    }
}
