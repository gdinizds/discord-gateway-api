package com.discord.gateway.executor;

import com.discord.gateway.model.DispatchResult;
import com.discord.gateway.model.OutboundAttachment;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.relay.AttachmentDownloader;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DiscordResponseExecutor {

    private static final Logger log = LoggerFactory.getLogger(DiscordResponseExecutor.class);

    private final InteractionHookRegistry hookRegistry;
    private final BotMessageRegistry botMessageRegistry;
    private final AttachmentDownloader attachmentDownloader;
    private final JDA jda;

    public DiscordResponseExecutor(InteractionHookRegistry hookRegistry,
                                   BotMessageRegistry botMessageRegistry,
                                   AttachmentDownloader attachmentDownloader,
                                   JDA jda) {
        this.hookRegistry = hookRegistry;
        this.botMessageRegistry = botMessageRegistry;
        this.attachmentDownloader = attachmentDownloader;
        this.jda = jda;
    }

    public DispatchResult execute(OutboundResponsePayload payload) {
        boolean hasToken = payload.interactionToken() != null && !payload.interactionToken().isBlank();
        try {
            return hasToken ? executeViaHook(payload) : executeViaChannel(payload);
        } catch (Exception e) {
            log.error("Discord dispatch failed [responseType={}]", payload.responseType(), e);
            return DispatchResult.failure(e.getMessage());
        }
    }

    private DispatchResult executeViaHook(OutboundResponsePayload payload) {
        var hookOpt = hookRegistry.getHook(payload.interactionToken());
        if (hookOpt.isEmpty()) {
            log.warn("No hook registered for interactionToken [responseType={}]", payload.responseType());
            return DispatchResult.failure("no hook found for the provided interactionToken");
        }
        InteractionHook hook = hookOpt.get();
        if (Boolean.TRUE.equals(payload.finished())) {
            hookRegistry.remove(payload.interactionToken());
        }
        return switch (payload.responseType()) {
            case "REPLY"           -> sendViaHook(hook, payload, false);
            case "EPHEMERAL_REPLY" -> sendViaHook(hook, payload, true);
            case "DEFERRED_REPLY",
                 "UPDATE_MESSAGE",
                 "DEFERRED_UPDATE" -> editViaHook(hook, payload);
            default -> DispatchResult.failure("unmapped responseType: " + payload.responseType());
        };
    }

    private DispatchResult sendViaHook(InteractionHook hook, OutboundResponsePayload payload, boolean ephemeral) {
        String content = payload.content() != null ? payload.content() : "";
        var action = hook.sendMessage(content);
        if (ephemeral) action.setEphemeral(true);

        var embeds = buildEmbeds(payload.embeds());
        if (!embeds.isEmpty()) action.addEmbeds(embeds);

        var files = downloadFiles(payload.attachments());
        if (!files.isEmpty()) action.addFiles(files);

        action.queue(
                msg -> log.debug("Hook message sent [messageId={}]", msg.getId()),
                err -> {
                    log.error("Failed to send hook message", err);
                    hook.sendMessage("Ocorreu uma falha ao enviar a resposta.")
                            .setEphemeral(true).queue(null, e -> log.warn("Failed to send hook error fallback", e));
                });
        return DispatchResult.success(null, null);
    }

    private DispatchResult editViaHook(InteractionHook hook, OutboundResponsePayload payload) {
        String content = payload.content() != null ? payload.content() : "";
        var action = hook.editOriginal(content);

        var embeds = buildEmbeds(payload.embeds());
        if (!embeds.isEmpty()) action.setEmbeds(embeds);

        var files = downloadFiles(payload.attachments());
        if (!files.isEmpty()) action.setFiles(files);

        action.queue(
                msg -> log.debug("Hook message edited [messageId={}]", msg.getId()),
                err -> {
                    log.error("Failed to edit hook message", err);
                    hook.sendMessage("Ocorreu uma falha ao editar a resposta.")
                            .setEphemeral(true).queue(null, e -> log.warn("Failed to send hook error fallback", e));
                });
        return DispatchResult.success(null, null);
    }

    private DispatchResult executeViaChannel(OutboundResponsePayload payload) {
        var channel = jda.getTextChannelById(payload.channelId());
        if (channel == null) {
            log.warn("Channel not found [channelId={}]", payload.channelId());
            return DispatchResult.failure("channel not found: " + payload.channelId());
        }
        return switch (payload.responseType()) {
            case "REPLY"          -> sendViaChannel(channel, payload);
            case "UPDATE_MESSAGE" -> editViaChannel(channel, payload);
            default -> DispatchResult.failure("responseType " + payload.responseType() + " not supported for channel dispatch");
        };
    }

    private DispatchResult sendViaChannel(
            net.dv8tion.jda.api.entities.channel.concrete.TextChannel channel,
            OutboundResponsePayload payload) {
        String content = payload.content() != null ? payload.content() : "";
        var action = channel.sendMessage(content);

        if (payload.messageId() != null) {
            action.setMessageReference(payload.messageId()).failOnInvalidReply(false);
        }

        var embeds = buildEmbeds(payload.embeds());
        if (!embeds.isEmpty()) action.addEmbeds(embeds);

        var files = downloadFiles(payload.attachments());
        if (!files.isEmpty()) action.addFiles(files);

        action.queue(
                msg -> {
                    log.debug("Channel message sent [messageId={}]", msg.getId());
                    if (payload.messageId() != null) {
                        botMessageRegistry.register(payload.messageId(), msg.getId());
                    }
                },
                err -> {
                    log.error("Failed to send channel message", err);
                    var fallback = channel.sendMessage("Ocorreu uma falha ao enviar a resposta.");
                    if (payload.messageId() != null) fallback.setMessageReference(payload.messageId()).failOnInvalidReply(false);
                    fallback.queue(null, e -> log.warn("Failed to send channel error fallback", e));
                });
        return DispatchResult.success(null, null);
    }

    private DispatchResult editViaChannel(
            net.dv8tion.jda.api.entities.channel.concrete.TextChannel channel,
            OutboundResponsePayload payload) {
        String content = payload.content() != null ? payload.content() : "";
        String targetId = botMessageRegistry.getBotMessageId(payload.messageId())
                .orElse(payload.messageId());
        var action = channel.editMessageById(targetId, content);

        var embeds = buildEmbeds(payload.embeds());
        if (!embeds.isEmpty()) action.setEmbeds(embeds);

        var files = downloadFiles(payload.attachments());
        if (!files.isEmpty()) action.setFiles(files);

        action.queue(
                msg -> log.debug("Channel message edited [messageId={}]", msg.getId()),
                err -> {
                    log.error("Failed to edit channel message", err);
                    channel.sendMessage("Ocorreu uma falha ao editar a resposta.")
                            .queue(null, e -> log.warn("Failed to send channel error fallback", e));
                });
        return DispatchResult.success(null, null);
    }

    private List<MessageEmbed> buildEmbeds(List<Map<String, Object>> embedMaps) {
        if (embedMaps == null || embedMaps.isEmpty()) return List.of();
        List<MessageEmbed> embeds = new ArrayList<>();
        for (var map : embedMaps) {
            var eb = new EmbedBuilder();
            if (map.get("title")       instanceof String s) eb.setTitle(s);
            if (map.get("description") instanceof String s) eb.setDescription(s);
            if (map.get("url")         instanceof String s) eb.setUrl(s);
            if (map.get("color")       instanceof Number n) eb.setColor(n.intValue());
            if (map.get("footer")      instanceof Map<?, ?> f) {
                eb.setFooter(
                        f.get("text")     instanceof String s ? s : null,
                        f.get("icon_url") instanceof String s ? s : null);
            }
            if (map.get("image")     instanceof Map<?, ?> img && img.get("url") instanceof String s) eb.setImage(s);
            if (map.get("thumbnail") instanceof Map<?, ?> th  && th.get("url")  instanceof String s) eb.setThumbnail(s);
            if (map.get("author")    instanceof Map<?, ?> a) {
                eb.setAuthor(
                        a.get("name")     instanceof String s ? s : null,
                        a.get("url")      instanceof String s ? s : null,
                        a.get("icon_url") instanceof String s ? s : null);
            }
            if (map.get("fields") instanceof List<?> fields) {
                for (var f : fields) {
                    if (f instanceof Map<?, ?> field) {
                        eb.addField(
                                field.get("name")   instanceof String s ? s : "",
                                field.get("value")  instanceof String s ? s : "",
                                Boolean.TRUE.equals(field.get("inline")));
                    }
                }
            }
            embeds.add(eb.build());
        }
        return embeds;
    }

    private List<FileUpload> downloadFiles(List<OutboundAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return List.of();
        List<FileUpload> files = new ArrayList<>();
        for (var att : attachments) {
            try {
                byte[] bytes;
                try (var stream = attachmentDownloader.download(att.url())) {
                    bytes = stream.readAllBytes();
                }
                var upload = FileUpload.fromData(bytes, att.name());
                if (att.description() != null) upload.setDescription(att.description());
                files.add(upload);
            } catch (Exception e) {
                log.error("Failed to download attachment [url={}, name={}]", att.url(), att.name(), e);
            }
        }
        return files;
    }
}
