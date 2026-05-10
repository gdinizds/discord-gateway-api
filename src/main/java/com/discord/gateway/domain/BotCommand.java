package com.discord.gateway.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(schema = "gateway", name = "bot_command")
public class BotCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guild_id", length = 20)
    private String guildId;

    @Column(name = "bot_id", length = 20)
    private String botId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommandPrefix prefix;

    @Column(name = "command_name", nullable = false, length = 64)
    private String name;

    @Column(length = 256)
    private String description;

    @Column(columnDefinition = "text")
    private String parameters;

    @Column(nullable = false)
    private int version;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "discord_command_id", length = 20)
    private String discordCmdId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BotCommand() {}

    public BotCommand(String guildId, String botId, CommandPrefix prefix,
                      String name, String description, String parameters) {
        this.guildId = guildId;
        this.botId = botId;
        this.prefix = prefix;
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.version = 1;
        this.deleted = false;
        this.createdAt = OffsetDateTime.now();
    }

    public void update(String description, String parameters, boolean deleted) {
        this.description = description;
        this.parameters = parameters;
        this.deleted = deleted;
        this.version++;
    }

    public void setDiscordCmdId(String id) { this.discordCmdId = id; }

    public Long getId()             { return id; }
    public String getGuildId()      { return guildId; }
    public String getBotId()        { return botId; }
    public CommandPrefix getPrefix(){ return prefix; }
    public String getName()         { return name; }
    public String getDescription()  { return description; }
    public String getParameters()   { return parameters; }
    public int getVersion()         { return version; }
    public boolean isDeleted()      { return deleted; }
    public String getDiscordCmdId() { return discordCmdId; }
}
