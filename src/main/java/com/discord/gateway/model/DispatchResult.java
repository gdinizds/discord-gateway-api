package com.discord.gateway.model;

public record DispatchResult(
        boolean success,
        String discordMessageId,
        String discordChannelId,
        String discordError
) {
    public static DispatchResult success(String messageId, String channelId) {
        return new DispatchResult(true, messageId, channelId, null);
    }

    public static DispatchResult failure(String error) {
        return new DispatchResult(false, null, null, error);
    }
}
