package com.discord.gateway.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(schema = "gateway", name = "safesearch_config")
public class SafeSearchConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String guildId;

    @Column(nullable = false, length = 20)
    private String channelId;

    @Column(nullable = false)
    private int level;

    @Column(nullable = false, length = 20)
    private String updatedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected SafeSearchConfig() {}

    public SafeSearchConfig(String guildId, String channelId, int level, String updatedBy) {
        this.guildId = guildId;
        this.channelId = channelId != null ? channelId : "";
        this.level = level;
        this.updatedBy = updatedBy;
    }

    public Long getId() { return id; }
    public String getGuildId() { return guildId; }
    public String getChannelId() { return channelId; }
    public int getLevel() { return level; }
    public String getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void update(int newLevel, String updatedBy) {
        this.level = newLevel;
        this.updatedBy = updatedBy;
    }
}
