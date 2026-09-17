package com.discord.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboundAttachment(
        String url,
        String name,
        String description
) {
}
