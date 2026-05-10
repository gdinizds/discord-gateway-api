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

        String guildId      = event.getGuild().getId();
        String channelId    = event.getChannel().getId();
        String userId       = event.getAuthor().getId();
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

            Map<String, Object> raw = buildMessageRaw(
                    event.getMessage(), userId, guildId, event.getGuild());

            var payload = new DiscordEventPayload(
                    "MESSAGE_CREATED", correlationId, "normal",
                    guildId, channelId, userId, null, messageId, 1, relayedUrls, raw);

            eventRouter.route(payload, null);
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
        String userId    = event.getAuthor().getId();
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

            Map<String, Object> raw = buildMessageRaw(
                    event.getMessage(), userId, guildId, event.getGuild());

            var payload = new DiscordEventPayload(
                    "MESSAGE_UPDATED", correlationId, "low",
                    guildId, channelId, userId, null, messageId, version, relayedUrls, raw);

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
        String userId       = event.getUser().getId();
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

            var payload = new DiscordEventPayload(
                    "INTERACTION_COMMAND", correlationId, "normal",
                    guildId, channelId, userId, token, null, 1, List.of(),
                    Map.of("commandName", event.getFullCommandName(), "args", args));

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
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;

        String guildId      = event.getGuild().getId();
        String channelId    = event.getChannel().getId();
        String userId       = event.getUser().getId();
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
            var payload = new DiscordEventPayload(
                    "INTERACTION_BUTTON", correlationId, "normal",
                    guildId, channelId, userId, token, messageId, 1, List.of(),
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
        String userId       = event.getUser().getId();
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

            var payload = new DiscordEventPayload(
                    "INTERACTION_MODAL", correlationId, "normal",
                    guildId, channelId, userId, token, null, 1, List.of(),
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
        String userId       = event.getMember().getUser().getId();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "GUILD_MEMBER");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("priority", "low");
        MDC.put("has_attachments", "false");
        try {
            var payload = new DiscordEventPayload(
                    "GUILD_MEMBER", correlationId, "low",
                    guildId, null, userId, null, null, 1, List.of(),
                    Map.of("action", "JOIN", "userId", userId,
                            "username", event.getMember().getUser().getName(),
                            "guildName", event.getGuild().getName()));

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
        String userId       = event.getUser().getId();
        String correlationId = newCorrelationId();

        MDC.put("event_type", "GUILD_MEMBER");
        MDC.put("guild_id", guildId);
        MDC.put("correlation_id", correlationId);
        MDC.put("priority", "low");
        MDC.put("has_attachments", "false");
        try {
            var payload = new DiscordEventPayload(
                    "GUILD_MEMBER", correlationId, "low",
                    guildId, null, userId, null, null, 1, List.of(),
                    Map.of("action", "LEAVE", "userId", userId,
                            "username", event.getUser().getName(),
                            "guildName", event.getGuild().getName()));

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
            var payload = new DiscordEventPayload(
                    "GUILD_UPDATED", correlationId, "low",
                    guildId, null, null, null, null, 1, List.of(),
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

    private static Map<String, Object> buildMessageRaw(
            net.dv8tion.jda.api.entities.Message message,
            String userId, String guildId,
            net.dv8tion.jda.api.entities.Guild guild) {

        Map<String, Object> raw = new HashMap<>();
        raw.put("content", message.getContentRaw());
        raw.put("user", new UserInfo(userId, message.getAuthor().getName(),
                message.getAuthor().getEffectiveAvatarUrl()));
        raw.put("guild", new GuildInfo(guildId, guild.getName(), guild.getIconUrl()));

        var ref = message.getReferencedMessage();
        if (ref != null) {
            raw.put("referencedMessage", new ReferencedMessageInfo(
                    ref.getId(), ref.getContentRaw(), ref.getAuthor().getId()));
        }
        return raw;
    }

    private static String newCorrelationId() {
        return Generators.timeBasedEpochGenerator().generate().toString();
    }
}
