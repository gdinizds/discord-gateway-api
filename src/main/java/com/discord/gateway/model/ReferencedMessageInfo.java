package com.discord.gateway.model;

public record ReferencedMessageInfo(
        String messageId,
        String content,
        String userId
) {}
