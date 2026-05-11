package com.discord.gateway.listener;

import com.discord.gateway.audit.GuildConfigService;
import com.discord.gateway.audit.GuildLifecycleService;
import com.discord.gateway.audit.InboundEventLogService;
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
import io.micrometer.core.instrument.MeterRegistry;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.guild.update.GuildUpdateNameEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DiscordEventListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordEventListener.class);

    private final EventRouter eventRouter;
    private final InboundEventLogService inboundEventLogService;
    private final AttachmentRelayService attachmentRelayService;
    private final InteractionHookRegistry hookRegistry;
    private final GuildLifecycleService guildLifecycleService;
    private final GuildConfigService guildConfigService;
    private final TopicRegistry topicRegistry;
    private final MeterRegistry meterRegistry;

    public DiscordEventListener(EventRouter eventRouter,
                                InboundEventLogService inboundEventLogService,
                                AttachmentRelayService attachmentRelayService,
                                InteractionHookRegistry hookRegistry,
                                GuildLifecycleService guildLifecycleService,
                                GuildConfigService guildConfigService,
                                TopicRegistry topicRegistry,
                                MeterRegistry meterRegistry) {
        this.eventRouter = eventRouter;
        this.inboundEventLogService = inboundEventLogService;
        this.attachmentRelayService = attachmentRelayService;
        this.hookRegistry = hookRegistry;
        this.guildLifecycleService = guildLifecycleService;
        this.guildConfigService = guildConfigService;
        this.topicRegistry = topicRegistry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;

        String content = event.getMessage().getContentRaw();
        if (content.startsWith(".") && content.length() > 1 && !content.startsWith(". ")) {
            handleDotCommand(event, content);
            return;
        }

        String guildId      = event.getGuild().getId();
        String channelId    = event.getChannel().getId();
        String messageId    = event.getMessage().getId();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "MESSAGE_CREATED");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("topic", topicRegistry.get("MESSAGE_CREATED").topic());
        MDC.put("priority", "normal");
        try {
            List<String> relayedUrls = relayAttachments(
                    event.getMessage().getAttachments(), guildId, messageId, "MESSAGE_CREATED", null);
            if (relayedUrls == null) return;

            MDC.put("has_attachments", String.valueOf(!relayedUrls.isEmpty()));

            var user = UserInfo.builder()
                    .id(event.getAuthor().getId())
                    .username(event.getAuthor().getName())
                    .avatarUrl(event.getAuthor().getEffectiveAvatarUrl())
                    .build();
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getGuild().getName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            var payload = new DiscordEventPayload(
                    "MESSAGE_CREATED", correlationId, "normal",
                    guild, channelId, user, null, messageId, 1,
                    relayedUrls, buildMessageRaw(event.getMessage()));

            eventRouter.route(payload, null);
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    private void handleDotCommand(MessageReceivedEvent event, String content) {
        String withoutDot = content.substring(1).trim();
        String[] parts = withoutDot.split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        List<String> args = parts.length > 1 && !parts[1].isBlank()
                ? Arrays.asList(parts[1].trim().split("\\s+"))
                : List.of();

        String guildId      = event.getGuild().getId();
        String channelId    = event.getChannel().getId();
        String messageId    = event.getMessage().getId();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "MESSAGE_COMMAND");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("topic", topicRegistry.get("MESSAGE_COMMAND").topic());
        MDC.put("priority", "normal");
        MDC.put("has_attachments", "false");
        try {
            var user = UserInfo.builder()
                    .id(event.getAuthor().getId())
                    .username(event.getAuthor().getName())
                    .avatarUrl(event.getAuthor().getEffectiveAvatarUrl())
                    .build();
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getGuild().getName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            var rawPayload = args.isEmpty() ? Map.<String, Object>of() : Map.<String, Object>of("args", args);
            var payload = new DiscordEventPayload(
                    "MESSAGE_COMMAND", correlationId, "normal",
                    guild, channelId, user, null, messageId, 1, List.of(), rawPayload);

            eventRouter.route(payload, null, Map.of("command-name", commandName));
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;

        String guildId   = event.getGuild().getId();
        String channelId = event.getChannel().getId();
        String messageId = event.getMessage().getId();

        MDC.put("event_type", "MESSAGE_UPDATED");
        MDC.put("guild_id", guildId);
        MDC.put("topic", topicRegistry.get("MESSAGE_UPDATED").topic());
        MDC.put("priority", "low");
        try {
            var correlationInfo = inboundEventLogService.findCorrelation(messageId);
            String correlationId = correlationInfo.map(c -> c.correlationId().toString()).orElse(newCorrelationId());
            int version = correlationInfo.map(c -> c.maxVersion() + 1).orElse(1);

            MDC.put("correlation_id", correlationId);

            List<String> relayedUrls = relayAttachments(
                    event.getMessage().getAttachments(), guildId, messageId, "MESSAGE_UPDATED", null);
            if (relayedUrls == null) return;

            MDC.put("has_attachments", String.valueOf(!relayedUrls.isEmpty()));

            var user = UserInfo.builder()
                    .id(event.getAuthor().getId())
                    .username(event.getAuthor().getName())
                    .avatarUrl(event.getAuthor().getEffectiveAvatarUrl())
                    .build();
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getGuild().getName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            var payload = new DiscordEventPayload(
                    "MESSAGE_UPDATED", correlationId, "low",
                    guild, channelId, user, null, messageId, version,
                    relayedUrls, buildMessageRaw(event.getMessage()));

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

        String guildId      = event.getGuild().getId();
        String channelId    = event.getChannel().getId();
        String token        = event.getToken();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "INTERACTION_COMMAND");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("topic", topicRegistry.get("INTERACTION_COMMAND").topic());
        MDC.put("priority", "normal");
        MDC.put("has_attachments", "false");
        try {
            Map<String, Object> args = new HashMap<>();
            for (var opt : event.getOptions()) args.put(opt.getName(), opt.getAsString());

            var user = UserInfo.builder()
                    .id(event.getUser().getId())
                    .username(event.getUser().getName())
                    .avatarUrl(event.getUser().getEffectiveAvatarUrl())
                    .build();
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getGuild().getName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            String commandName = event.getFullCommandName();
            var rawPayload = args.isEmpty() ? Map.<String, Object>of() : Map.<String, Object>of("args", args);
            var payload = new DiscordEventPayload(
                    "INTERACTION_COMMAND", correlationId, "normal",
                    guild, channelId, user, token, null, 1, List.of(), rawPayload);

            boolean published = eventRouter.route(payload,
                    () -> event.reply("Serviço temporariamente indisponível. Tente novamente em instantes.")
                            .setEphemeral(true).queue(),
                    Map.of("command-name", commandName));
            if (published) {
                event.deferReply().queue(hook -> hookRegistry.register(token, hook));
            }
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;

        String guildId      = event.getGuild().getId();
        String channelId    = event.getChannel().getId();
        String token        = event.getToken();
        String messageId    = event.getMessage().getId();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "INTERACTION_BUTTON");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("topic", topicRegistry.get("INTERACTION_BUTTON").topic());
        MDC.put("priority", "normal");
        MDC.put("has_attachments", "false");
        try {
            var user = UserInfo.builder()
                    .id(event.getUser().getId())
                    .username(event.getUser().getName())
                    .avatarUrl(event.getUser().getEffectiveAvatarUrl())
                    .build();
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getGuild().getName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            var payload = new DiscordEventPayload(
                    "INTERACTION_BUTTON", correlationId, "normal",
                    guild, channelId, user, token, messageId, 1, List.of(),
                    Map.of("componentId", event.getComponentId(), "messageId", messageId));

            boolean published = eventRouter.route(payload,
                    () -> event.reply("Serviço temporariamente indisponível. Tente novamente em instantes.")
                            .setEphemeral(true).queue());
            if (published) {
                event.deferReply().queue(hook -> hookRegistry.register(token, hook));
            }
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;

        String guildId      = event.getGuild().getId();
        String channelId    = event.getChannel().getId();
        String token        = event.getToken();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "INTERACTION_MODAL");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("topic", topicRegistry.get("INTERACTION_MODAL").topic());
        MDC.put("priority", "normal");
        MDC.put("has_attachments", "false");
        try {
            Map<String, Object> values = new HashMap<>();
            for (var mapping : event.getValues()) values.put(mapping.getId(), mapping.getAsString());

            var user = UserInfo.builder()
                    .id(event.getUser().getId())
                    .username(event.getUser().getName())
                    .avatarUrl(event.getUser().getEffectiveAvatarUrl())
                    .build();
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getGuild().getName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            var payload = new DiscordEventPayload(
                    "INTERACTION_MODAL", correlationId, "normal",
                    guild, channelId, user, token, null, 1, List.of(),
                    Map.of("modalId", event.getModalId(), "values", values));

            boolean published = eventRouter.route(payload,
                    () -> event.reply("Serviço temporariamente indisponível. Tente novamente em instantes.")
                            .setEphemeral(true).queue());
            if (published) {
                event.deferReply().queue(hook -> hookRegistry.register(token, hook));
            }
            inboundEventLogService.log(payload);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        String guildId      = event.getGuild().getId();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "GUILD_MEMBER");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("priority", "low");
        MDC.put("has_attachments", "false");
        try {
            var jdaUser = event.getMember().getUser();
            var user = UserInfo.builder()
                    .id(jdaUser.getId())
                    .username(jdaUser.getName())
                    .avatarUrl(jdaUser.getEffectiveAvatarUrl())
                    .build();
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getGuild().getName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            var payload = new DiscordEventPayload(
                    "GUILD_MEMBER", correlationId, "low",
                    guild, null, user, null, null, 1, List.of(),
                    Map.of("action", "JOIN"));

            eventRouter.route(payload, null);
            inboundEventLogService.log(payload);
            guildLifecycleService.onJoin(event.getGuild());
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        String guildId      = event.getGuild().getId();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "GUILD_MEMBER");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("priority", "low");
        MDC.put("has_attachments", "false");
        try {
            var user = UserInfo.builder()
                    .id(event.getUser().getId())
                    .username(event.getUser().getName())
                    .avatarUrl(event.getUser().getEffectiveAvatarUrl())
                    .build();
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getGuild().getName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            var payload = new DiscordEventPayload(
                    "GUILD_MEMBER", correlationId, "low",
                    guild, null, user, null, null, 1, List.of(),
                    Map.of("action", "LEAVE"));

            eventRouter.route(payload, null);
            inboundEventLogService.log(payload);
            guildLifecycleService.onLeave(guildId, event.getGuild().getName());
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onGuildUpdateName(GuildUpdateNameEvent event) {
        String guildId      = event.getGuild().getId();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "GUILD_UPDATED");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("priority", "low");
        MDC.put("has_attachments", "false");
        try {
            var guild = GuildInfo.builder()
                    .id(guildId)
                    .name(event.getNewName())
                    .iconUrl(event.getGuild().getIconUrl())
                    .build();

            var payload = new DiscordEventPayload(
                    "GUILD_UPDATED", correlationId, "low",
                    guild, null, null, null, null, 1, List.of(),
                    Map.of("field", "name",
                            "oldValue", event.getOldName(),
                            "newValue", event.getNewName()));

            eventRouter.route(payload, null);
            inboundEventLogService.log(payload);
            guildLifecycleService.onUpdate(event.getGuild(), "NAME_CHANGED",
                    Map.of("oldName", event.getOldName(), "newName", event.getNewName()));
        } finally {
            MDC.clear();
        }
    }

    private void handleConfigCommand(SlashCommandInteractionEvent event) {
        String guildId = event.getGuild().getId();

        if (!event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("Você precisa da permissão **Gerenciar Servidor** para usar este comando.")
                    .setEphemeral(true).queue();
            meterRegistry.counter("discord.gateway.config.commands",
                    "success", "false", "reason", "unauthorized").increment();
            return;
        }

        String paramName = event.getOption("parameter").getAsString();
        String value     = event.getOption("value").getAsString();

        GuildParam param;
        try {
            param = GuildParam.valueOf(paramName);
        } catch (IllegalArgumentException e) {
            event.reply("Parâmetro desconhecido: `" + paramName + "`.")
                    .setEphemeral(true).queue();
            return;
        }

        var result = guildConfigService.upsert(guildId, param, value);
        event.reply(result.message()).setEphemeral(true).queue();
        meterRegistry.counter("discord.gateway.config.commands",
                "success", String.valueOf(result.success())).increment();
    }

    private List<String> relayAttachments(
            List<net.dv8tion.jda.api.entities.Message.Attachment> attachments,
            String guildId, String messageId, String eventType, Runnable ephemeralFallback) {
        if (attachments.isEmpty()) return List.of();
        if (!guildConfigService.isRelayEnabled(guildId)) {
            log.debug("Attachment relay disabled for guild {} — publishing event without attachments", guildId);
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        try {
            for (var att : attachments) {
                String url = attachmentRelayService.relay(
                        att.getUrl(), att.getFileName(), att.getSize(), guildId, messageId);
                urls.add(url);
            }
            return urls;
        } catch (AttachmentRelayException e) {
            log.warn("Attachment relay failed — event discarded [eventType={}, messageId={}]", eventType, messageId, e);
            meterRegistry.counter("discord.gateway.events.discarded",
                    "type", eventType, "reason", "attachment_relay_failure").increment();
            if (ephemeralFallback != null) ephemeralFallback.run();
            return null;
        }
    }

    private static Map<String, Object> buildMessageRaw(net.dv8tion.jda.api.entities.Message message) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("content", message.getContentRaw());
        var ref = message.getReferencedMessage();
        if (ref != null) {
            raw.put("referencedMessage", ReferencedMessageInfo.builder()
                    .messageId(ref.getId())
                    .content(ref.getContentRaw())
                    .userId(ref.getAuthor().getId())
                    .build());
        }
        return raw;
    }

    private static String newCorrelationId() {
        return Generators.timeBasedEpochGenerator().generate().toString();
    }
}
