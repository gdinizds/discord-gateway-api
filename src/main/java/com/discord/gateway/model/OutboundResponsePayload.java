package com.discord.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboundResponsePayload(
        String responseType,
        String interactionToken,
        String messageId,
        String channelId,
        String content,
        List<Map<String, Object>> embeds,
        List<OutboundAttachment> attachments,
        Boolean finished,
        String correlationId
) {
    public boolean hasContent() {
        return (content != null && !content.isBlank())
                || (embeds != null && !embeds.isEmpty())
                || (attachments != null && !attachments.isEmpty());
    }

    public boolean isDeferred() {
        return "DEFERRED_REPLY".equals(responseType) || "DEFERRED_UPDATE".equals(responseType);
    }
}
