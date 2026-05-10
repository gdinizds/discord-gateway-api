package com.discord.gateway.dispatcher;

import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.model.PayloadValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Set;

@Component
public class ResponseValidator {

    private static final Set<String> VALID_TYPES = Set.of(
            "REPLY", "EPHEMERAL_REPLY", "UPDATE_MESSAGE", "DEFERRED_REPLY", "DEFERRED_UPDATE");

    private static final Set<String> CONTENT_REQUIRED_TYPES = Set.of(
            "REPLY", "EPHEMERAL_REPLY", "UPDATE_MESSAGE", "DEFERRED_REPLY");

    public void validate(OutboundResponsePayload payload, String rawJson) {
        var missing = new ArrayList<String>();

        if (payload.responseType() == null || payload.responseType().isBlank()) {
            missing.add("responseType");
            throw new PayloadValidationException(
                    "responseType ausente", missing, rawJson);
        }

        if (!VALID_TYPES.contains(payload.responseType())) {
            throw new PayloadValidationException(
                    "responseType desconhecido: " + payload.responseType(), missing, rawJson);
        }

        if (payload.interactionToken() == null || payload.interactionToken().isBlank()) {
            missing.add("interactionToken");
        }

        if ("UPDATE_MESSAGE".equals(payload.responseType()) || "DEFERRED_UPDATE".equals(payload.responseType())) {
            if (payload.messageId() == null || payload.messageId().isBlank()) {
                missing.add("messageId");
            }
        }

        if (CONTENT_REQUIRED_TYPES.contains(payload.responseType()) && !payload.hasContent()) {
            missing.add("content");
            missing.add("embeds");
        }

        if (!missing.isEmpty()) {
            throw new PayloadValidationException(
                    "campos obrigatórios ausentes para " + payload.responseType(), missing, rawJson);
        }
    }
}
