package com.discord.gateway.model;

import java.util.List;

public class PayloadValidationException extends RuntimeException {

    private final String reason;
    private final List<String> missingFields;
    private final String receivedPayload;

    public PayloadValidationException(String reason, List<String> missingFields, String receivedPayload) {
        super(reason);
        this.reason = reason;
        this.missingFields = missingFields != null ? List.copyOf(missingFields) : List.of();
        this.receivedPayload = receivedPayload;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }

    public String getReceivedPayload() {
        return receivedPayload;
    }
}
