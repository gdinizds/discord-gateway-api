package com.discord.gateway.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(schema = "gateway", name = "message_log")
public class MessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID correlationId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private MessageDirection direction;

    @Column(length = 64)
    private String eventType;

    @Column(length = 20)
    private String discordMessageId;

    @Column(length = 20)
    private String discordChannelId;

    @Column(nullable = false, length = 20)
    private String guildId;

    @Column(length = 20)
    private String discordUserId;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private OffsetDateTime recordedAt;

    protected MessageLog() {}

    public MessageLog(UUID correlationId, int version, MessageDirection direction,
                      String eventType, String discordMessageId, String discordChannelId,
                      String guildId, String discordUserId, String payload) {
        this.correlationId = correlationId;
        this.version = version;
        this.direction = direction;
        this.eventType = eventType;
        this.discordMessageId = discordMessageId;
        this.discordChannelId = discordChannelId;
        this.guildId = guildId;
        this.discordUserId = discordUserId;
        this.payload = payload;
        this.recordedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
}
