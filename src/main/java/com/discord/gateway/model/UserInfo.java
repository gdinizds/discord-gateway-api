package com.discord.gateway.model;

public record UserInfo(
        String id,
        String username,
        String avatarUrl
) {}
