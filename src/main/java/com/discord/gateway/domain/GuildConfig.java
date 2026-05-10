package com.discord.gateway.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(schema = "gateway", name = "guild_config")
public class GuildConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String guildId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuildParam param;

    @Column(nullable = false, columnDefinition = "text")
    private String value;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected GuildConfig() {}

    public GuildConfig(String guildId, GuildParam param, String value) {
        this.guildId = guildId;
        this.param = param;
        this.value = value;
    }

    public Long getId()          { return id; }
    public String getGuildId()   { return guildId; }
    public GuildParam getParam() { return param; }
    public String getValue()     { return value; }

    public void update(String newValue) { this.value = newValue; }
}
