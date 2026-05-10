package com.discord.gateway.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(schema = "gateway", name = "bot_command_log")
public class BotCommandLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "command_id")
    private BotCommand command;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private CommandEventType eventType;

    @Column(name = "guild_id", length = 20)
    private String guildId;

    @Column(name = "bot_id", length = 20)
    private String botId;

    @Column(name = "discord_success")
    private Boolean discordSuccess;

    @Column(name = "discord_error", columnDefinition = "text")
    private String discordError;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    protected BotCommandLog() {}

    public BotCommandLog(BotCommand command, CommandEventType eventType,
                         String guildId, String botId,
                         boolean discordSuccess, String discordError) {
        this.command = command;
        this.eventType = eventType;
        this.guildId = guildId;
        this.botId = botId;
        this.discordSuccess = discordSuccess;
        this.discordError = discordError;
        this.recordedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
}
