package com.discord.gateway.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(schema = "gateway", name = "config_audit_log")
public class ConfigAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String guildId;

    @Column(length = 20)
    private String channelId;

    @Column(nullable = false, length = 20)
    private String userId;

    @Column(nullable = false, length = 64)
    private String settingKey;

    @Column(length = 255)
    private String oldValue;

    @Column(nullable = false, length = 255)
    private String newValue;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ConfigAuditLog() {}

    public ConfigAuditLog(String guildId, String channelId, String userId,
                          String settingKey, String oldValue, String newValue) {
        this.guildId = guildId;
        this.channelId = channelId;
        this.userId = userId;
        this.settingKey = settingKey;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Long getId() { return id; }
    public String getGuildId() { return guildId; }
    public String getChannelId() { return channelId; }
    public String getUserId() { return userId; }
    public String getSettingKey() { return settingKey; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
