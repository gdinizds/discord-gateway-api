package com.discord.gateway.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(schema = "gateway", name = "guild_registry")
public class GuildRegistry {

    @Id
    @Column(name = "guild_id", nullable = false, length = 20)
    private String guildId;

    @Column(length = 100)
    private String guildName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuildStatus status;

    private Integer memberCount;
    private Long botPermissions;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    private OffsetDateTime leftAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected GuildRegistry() {}

    public GuildRegistry(String guildId, String guildName, GuildStatus status,
                         Integer memberCount, Long botPermissions) {
        this.guildId = guildId;
        this.guildName = guildName;
        this.status = status;
        this.memberCount = memberCount;
        this.botPermissions = botPermissions;
        this.joinedAt = OffsetDateTime.now();
    }

    public void markActive(String guildName, Integer memberCount, Long botPermissions) {
        this.guildName = guildName;
        this.status = GuildStatus.ACTIVE;
        this.memberCount = memberCount;
        this.botPermissions = botPermissions;
        this.leftAt = null;
    }

    public void markLeft() {
        this.status = GuildStatus.LEFT;
        this.leftAt = OffsetDateTime.now();
    }

    public String getGuildId()       { return guildId; }
    public String getGuildName()     { return guildName; }
    public GuildStatus getStatus()   { return status; }
    public Integer getMemberCount()  { return memberCount; }
    public Long getBotPermissions()  { return botPermissions; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }
}
