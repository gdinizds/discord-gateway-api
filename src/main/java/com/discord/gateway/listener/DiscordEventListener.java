package com.discord.gateway.listener;

import com.discord.gateway.audit.GuildConfigService;
import com.discord.gateway.audit.GuildLifecycleService;
import com.discord.gateway.audit.InboundEventLogService;
import com.discord.gateway.command.EphemeralCommandRegistry;
import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.executor.InteractionHookRegistry;
import com.discord.gateway.model.AttachmentRelayException;
import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.model.GuildInfo;
import com.discord.gateway.model.ReferencedMessageInfo;
import com.discord.gateway.model.UserInfo;
import com.discord.gateway.relay.AttachmentRelayService;
import com.discord.gateway.router.EventRouter;
import com.discord.gateway.router.TopicRegistry;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.update.GuildUpdateBoostTierEvent;
import net.dv8tion.jda.api.events.guild.update.GuildUpdateNameEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DiscordEventListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordEventListener.class);
    private static final TimeBasedEpochGenerator UUID_GENERATOR = Generators.timeBasedEpochGenerator();

    static final String UNAVAILABLE_MESSAGE = "Serviço temporariamente indisponível. Tente novamente em instantes.";
    static final String CHANNEL_NOT_ALLOWED_MESSAGE = "O bot não está habilitado neste canal.";

    private final EventRouter eventRouter;
    private final InboundEventLogService inboundEventLogService;
    private final AttachmentRelayService attachmentRelayService;
    private final InteractionHookRegistry hookRegistry;
    private final GuildLifecycleService guildLifecycleService;
    private final GuildConfigService guildConfigService;
    private final TopicRegistry topicRegistry;
    private final MeterRegistry meterRegistry;
    private final EphemeralCommandRegistry ephemeralCommands;

    public DiscordEventListener(EventRouter eventRouter,
                                InboundEventLogService inboundEventLogService,
                                AttachmentRelayService attachmentRelayService,
                                InteractionHookRegistry hookRegistry,
                                GuildLifecycleService guildLifecycleService,
                                GuildConfigService guildConfigService,
                                TopicRegistry topicRegistry,
                                MeterRegistry meterRegistry,
                                EphemeralCommandRegistry ephemeralCommands) {
        this.eventRouter = eventRouter;
        this.inboundEventLogService = inboundEventLogService;
        this.attachmentRelayService = attachmentRelayService;
        this.hookRegistry = hookRegistry;
        this.guildLifecycleService = guildLifecycleService;
        this.guildConfigService = guildConfigService;
        this.topicRegistry = topicRegistry;
        this.meterRegistry = meterRegistry;
        this.ephemeralCommands = ephemeralCommands;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;

        String content = event.getMessage().getContentRaw();
        if (content.startsWith(".") && content.length() > 1 && !content.startsWith(". ")) {
            handleDotCommand(event, content);
            return;
        }

        String guildId = event.getGuild().getId();
        String messageId = event.getMessage().getId();
        String correlationId = newCorrelationId();

        putMdc("MESSAGE_CREATED", guildId, correlationId, "normal");
        try {
            List<String> relayedUrls = relayAttachments(
                    event.getMessage().getAttachments(), event.getGuild(), messageId, "MESSAGE_CREATED");
            if (relayedUrls == null) return;
            MDC.put("has_attachments", String.valueOf(!relayedUrls.isEmpty()));

            var payload = new DiscordEventPayload(
                    "MESSAGE_CREATED", correlationId, "normal",
                    guildInfo(event.getGuild()), event.getChannel().getId(), userInfo(event.getAuthor()),
                    null, messageId, 1, relayedUrls, buildMessageRaw(event.getMessage()));

            eventRouter.route(payload, null);
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    private void handleDotCommand(MessageReceivedEvent event, String content) {
        var parsed = DotCommandParser.parse(content);
        String guildId = event.getGuild().getId();
        String messageId = event.getMessage().getId();
        String correlationId = newCorrelationId();

        putMdc("MESSAGE_COMMAND", guildId, correlationId, "normal");
        try {
            List<String> relayedUrls = relayAttachments(
                    event.getMessage().getAttachments(), event.getGuild(), messageId, "MESSAGE_COMMAND");
            if (relayedUrls == null) return;
            MDC.put("has_attachments", String.valueOf(!relayedUrls.isEmpty()));

            var payload = new DiscordEventPayload(
                    "MESSAGE_COMMAND", correlationId, "normal",
                    guildInfo(event.getGuild()), event.getChannel().getId(), userInfo(event.getAuthor()),
                    null, messageId, 1, relayedUrls, buildDotCommandRaw(parsed, event.getMessage()));

            eventRouter.route(payload, null, Map.of("command-name", parsed.name()));
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;

        String guildId = event.getGuild().getId();
        String messageId = event.getMessage().getId();

        putMdc("MESSAGE_UPDATED", guildId, null, "low");
        try {
            var correlationInfo = inboundEventLogService.findCorrelation(messageId);
            String correlationId = correlationInfo.map(c -> c.correlationId().toString()).orElseGet(DiscordEventListener::newCorrelationId);
            int version = correlationInfo.map(c -> c.maxVersion() + 1).orElse(1);
            MDC.put("correlation_id", correlationId);

            List<String> relayedUrls = relayAttachments(
                    event.getMessage().getAttachments(), event.getGuild(), messageId, "MESSAGE_UPDATED");
            if (relayedUrls == null) return;
            MDC.put("has_attachments", String.valueOf(!relayedUrls.isEmpty()));

            var payload = new DiscordEventPayload(
                    "MESSAGE_UPDATED", correlationId, "low",
                    guildInfo(event.getGuild()), event.getChannel().getId(), userInfo(event.getAuthor()),
                    null, messageId, version, relayedUrls, buildMessageRaw(event.getMessage()));

            eventRouter.route(payload, null);
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) return;

        if ("config".equals(event.getName())) {
            handleConfigCommand(event);
            return;
        }

        String guildId = event.getGuild().getId();
        String correlationId = newCorrelationId();

        putMdc("INTERACTION_COMMAND", guildId, correlationId, "normal");
        MDC.put("has_attachments", "false");
        try {
            Map<String, Object> args = new HashMap<>();
            for (var opt : event.getOptions()) args.put(opt.getName(), opt.getAsString());

            var payload = new DiscordEventPayload(
                    "INTERACTION_COMMAND", correlationId, "normal",
                    guildInfo(event.getGuild()), event.getChannel().getId(), userInfo(event.getUser()),
                    event.getToken(), null, 1, List.of(),
                    args.isEmpty() ? Map.of() : Map.of("args", args));

            boolean ephemeral = ephemeralCommands.isEphemeral(guildId, event.getName());
            publishInteraction(event, payload, ephemeral, Map.of("command-name", event.getFullCommandName()));
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;

        String guildId = event.getGuild().getId();
        String messageId = event.getMessage().getId();
        String correlationId = newCorrelationId();

        putMdc("INTERACTION_BUTTON", guildId, correlationId, "normal");
        MDC.put("has_attachments", "false");
        try {
            var payload = new DiscordEventPayload(
                    "INTERACTION_BUTTON", correlationId, "normal",
                    guildInfo(event.getGuild()), event.getChannel().getId(), userInfo(event.getUser()),
                    event.getToken(), messageId, 1, List.of(),
                    Map.of("componentId", event.getComponentId(), "messageId", messageId));

            publishInteraction(event, payload, false, null);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;

        String guildId = event.getGuild().getId();
        String correlationId = newCorrelationId();

        putMdc("INTERACTION_MODAL", guildId, correlationId, "normal");
        MDC.put("has_attachments", "false");
        try {
            Map<String, Object> values = new HashMap<>();
            for (var mapping : event.getValues()) values.put(mapping.getId(), mapping.getAsString());

            var payload = new DiscordEventPayload(
                    "INTERACTION_MODAL", correlationId, "normal",
                    guildInfo(event.getGuild()), event.getChannel().getId(), userInfo(event.getUser()),
                    event.getToken(), null, 1, List.of(),
                    Map.of("modalId", event.getModalId(), "values", values));

            publishInteraction(event, payload, false, null);
        } finally {
            MDC.clear();
        }
    }

    private void publishInteraction(IReplyCallback event, DiscordEventPayload payload,
                                    boolean ephemeral, Map<String, String> headers) {
        String guildId = payload.guild().getId();
        if (!eventRouter.isChannelAllowed(guildId, payload.channelId())) {
            event.reply(CHANNEL_NOT_ALLOWED_MESSAGE).setEphemeral(true)
                    .queue(null, err -> log.warn("Failed to reply to filtered interaction", err));
            meterRegistry.counter("discord.gateway.events.discarded",
                    "type", payload.eventType(), "reason", "channel_filtered").increment();
            return;
        }

        String token = payload.interactionToken();
        var hook = event.getHook();
        hookRegistry.register(token, hook);
        event.deferReply(ephemeral).queue(null, err -> {
            log.warn("Failed to defer interaction [type={}]", payload.eventType(), err);
            hookRegistry.remove(token);
        });

        eventRouter.route(payload, () -> {
            hookRegistry.remove(token);
            hook.editOriginal(UNAVAILABLE_MESSAGE)
                    .queue(null, err -> log.warn("Failed to send unavailable fallback", err));
        }, headers);
        inboundEventLogService.log(payload);
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        guildLifecycleService.onJoin(event.getGuild());
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        guildLifecycleService.onLeave(event.getGuild().getId(), event.getGuild().getName());
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        publishMemberEvent(event.getGuild(), event.getUser(), "JOIN");
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        publishMemberEvent(event.getGuild(), event.getUser(), "LEAVE");
    }

    private void publishMemberEvent(Guild guild, User user, String action) {
        String correlationId = newCorrelationId();
        putMdc("GUILD_MEMBER", guild.getId(), correlationId, "low");
        MDC.put("has_attachments", "false");
        try {
            var payload = new DiscordEventPayload(
                    "GUILD_MEMBER", correlationId, "low",
                    guildInfo(guild), null, userInfo(user), null, null, 1, List.of(),
                    Map.of("action", action));

            eventRouter.route(payload, null);
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        var guilds = event.getJDA().getGuilds();
        log.info("Gateway ready — syncing tier for {} guild(s) in background", guilds.size());
        Thread.ofVirtual().name("tier-sync").start(() -> {
            for (var guild : guilds) {
                try {
                    var raw = new HashMap<String, Object>();
                    raw.put("field", "boostTier");
                    raw.put("sync", true);
                    raw.put("tier", buildTierInfo(guild));
                    publishGuildUpdate(guildInfo(guild), raw);
                } catch (Exception e) {
                    log.warn("Failed to sync tier for guild {} on startup", guild.getId(), e);
                }
            }
            log.info("Tier sync complete for {} guild(s)", guilds.size());
        });
    }

    @Override
    public void onGuildUpdateName(GuildUpdateNameEvent event) {
        var raw = new HashMap<String, Object>();
        raw.put("field", "name");
        raw.put("oldValue", event.getOldName());
        raw.put("newValue", event.getNewName());
        raw.put("tier", buildTierInfo(event.getGuild()));

        var guild = GuildInfo.builder()
                .id(event.getGuild().getId())
                .name(event.getNewName())
                .iconUrl(event.getGuild().getIconUrl())
                .build();

        publishGuildUpdate(guild, raw);
        guildLifecycleService.onUpdate(event.getGuild(), "NAME_CHANGED",
                Map.of("oldName", event.getOldName(), "newName", event.getNewName()));
    }

    @Override
    public void onGuildUpdateBoostTier(GuildUpdateBoostTierEvent event) {
        var raw = new HashMap<String, Object>();
        raw.put("field", "boostTier");
        raw.put("oldValue", event.getOldBoostTier().getKey());
        raw.put("newValue", event.getNewBoostTier().getKey());
        raw.put("tier", buildTierInfo(event.getGuild()));

        publishGuildUpdate(guildInfo(event.getGuild()), raw);
        guildLifecycleService.onUpdate(event.getGuild(), "BOOST_TIER_CHANGED",
                Map.of("oldTier", event.getOldBoostTier().getKey(),
                       "newTier", event.getNewBoostTier().getKey()));
    }

    private void publishGuildUpdate(GuildInfo guild, Map<String, Object> raw) {
        String correlationId = newCorrelationId();
        putMdc("GUILD_UPDATED", guild.getId(), correlationId, "low");
        MDC.put("has_attachments", "false");
        try {
            var payload = new DiscordEventPayload(
                    "GUILD_UPDATED", correlationId, "low",
                    guild, null, null, null, null, 1, List.of(), raw);

            eventRouter.route(payload, null);
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    private void handleConfigCommand(SlashCommandInteractionEvent event) {
        String guildId = event.getGuild().getId();

        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("Você precisa da permissão **Gerenciar Servidor** para usar este comando.")
                    .setEphemeral(true).queue();
            meterRegistry.counter("discord.gateway.config.commands",
                    "success", "false", "reason", "unauthorized").increment();
            return;
        }

        var paramOption = event.getOption("parameter");
        var valueOption = event.getOption("value");
        if (paramOption == null || valueOption == null) {
            event.reply("Informe `parameter` e `value`.").setEphemeral(true).queue();
            return;
        }
        String paramName = paramOption.getAsString();
        String value = valueOption.getAsString();

        GuildParam param;
        try {
            param = GuildParam.valueOf(paramName);
        } catch (IllegalArgumentException e) {
            event.reply("Parâmetro desconhecido: `" + paramName + "`.")
                    .setEphemeral(true).queue();
            return;
        }

        var result = guildConfigService.upsert(guildId, param, value);
        if (result.success() && param == GuildParam.ALLOWED_CHANNELS) {
            eventRouter.evictChannelFilterCache(guildId);
        }
        event.reply(result.message()).setEphemeral(true).queue();
        meterRegistry.counter("discord.gateway.config.commands",
                "success", String.valueOf(result.success())).increment();
    }

    private List<String> relayAttachments(List<Message.Attachment> attachments,
                                          Guild guild, String messageId, String eventType) {
        if (attachments.isEmpty()) return List.of();
        String guildId = guild.getId();
        if (!guildConfigService.isRelayEnabled(guildId)) {
            log.debug("Attachment relay disabled for guild {} — publishing event without attachments", guildId);
            return List.of();
        }
        long tierMaxSizeBytes = guild.getBoostTier().getMaxFileSize();
        List<String> urls = new ArrayList<>(attachments.size());
        try {
            for (var att : attachments) {
                urls.add(attachmentRelayService.relay(
                        att.getUrl(), att.getFileName(), att.getSize(), guildId, messageId, tierMaxSizeBytes));
            }
            return urls;
        } catch (AttachmentRelayException e) {
            log.warn("Attachment relay failed — event discarded [eventType={}, messageId={}]", eventType, messageId, e);
            meterRegistry.counter("discord.gateway.events.discarded",
                    "type", eventType, "reason", "attachment_relay_failure").increment();
            return null;
        }
    }

    private void putMdc(String eventType, String guildId, String correlationId, String priority) {
        MDC.put("event_type", eventType);
        MDC.put("guild_id", guildId);
        if (correlationId != null) MDC.put("correlation_id", correlationId);
        MDC.put("priority", priority);
        var mapping = topicRegistry.get(eventType);
        if (mapping != null) MDC.put("topic", mapping.topic());
    }

    private static UserInfo userInfo(User user) {
        return UserInfo.builder()
                .id(user.getId())
                .username(user.getName())
                .avatarUrl(user.getEffectiveAvatarUrl())
                .build();
    }

    private static GuildInfo guildInfo(Guild guild) {
        return GuildInfo.builder()
                .id(guild.getId())
                .name(guild.getName())
                .iconUrl(guild.getIconUrl())
                .build();
    }

    private static Map<String, Object> buildTierInfo(Guild guild) {
        var tier = guild.getBoostTier();
        var params = new HashMap<String, Object>();
        params.put("level", tier.getKey());
        params.put("boostCount", guild.getBoostCount());
        params.put("maxFileSizeBytes", tier.getMaxFileSize());
        params.put("maxFileSizeMb", tier.getMaxFileSize() / (1024 * 1024));
        params.put("maxBitrateKbps", tier.getMaxBitrate() / 1000);
        params.put("maxEmojis", tier.getMaxEmojis());
        return params;
    }

    private static Map<String, Object> buildMessageRaw(Message message) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("content", message.getContentRaw());
        putReferencedMessage(raw, message);
        return raw;
    }

    private static Map<String, Object> buildDotCommandRaw(DotCommandParser.ParsedDotCommand parsed, Message message) {
        Map<String, Object> raw = new HashMap<>();
        if (!parsed.args().isEmpty()) raw.put("args", parsed.args());
        if (!parsed.content().isEmpty()) raw.put("content", parsed.content());
        putReferencedMessage(raw, message);
        return raw;
    }

    private static void putReferencedMessage(Map<String, Object> raw, Message message) {
        var ref = message.getReferencedMessage();
        if (ref != null) {
            raw.put("referencedMessage", ReferencedMessageInfo.builder()
                    .messageId(ref.getId())
                    .content(ref.getContentRaw())
                    .userId(ref.getAuthor().getId())
                    .build());
        }
    }

    private static String newCorrelationId() {
        return UUID_GENERATOR.generate().toString();
    }
}
