package com.discord.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuildInfo(
        String id,
        String name,
        String iconUrl
) {}
