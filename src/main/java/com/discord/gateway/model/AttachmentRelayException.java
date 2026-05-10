package com.discord.gateway.model;

public class AttachmentRelayException extends RuntimeException {

    public AttachmentRelayException(String message) {
        super(message);
    }

    public AttachmentRelayException(String message, Throwable cause) {
        super(message, cause);
    }
}
