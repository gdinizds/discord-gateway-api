package com.discord.gateway.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(schema = "gateway", name = "guild_event_log")
public class GuildEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String guildId;

    @Column(nullable = false, length = 24)
    private String eventType;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private OffsetDateTime recordedAt;

    protected GuildEventLog() {}

    public GuildEventLog(String guildId, String eventType, String payload) {
        this.guildId = guildId;
        this.eventType = eventType;
        this.payload = payload;
        this.recordedAt = OffsetDateTime.now();
    }

    public Long getId()          { return id; }
    public String getGuildId()   { return guildId; }
    public String getEventType() { return eventType; }
}
