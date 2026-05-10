package com.discord.gateway.executor;

import com.discord.gateway.model.DispatchResult;
import com.discord.gateway.model.OutboundResponsePayload;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DiscordResponseExecutor {

    private static final Logger log = LoggerFactory.getLogger(DiscordResponseExecutor.class);

    private final InteractionHookRegistry hookRegistry;

    public DiscordResponseExecutor(InteractionHookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    public DispatchResult execute(OutboundResponsePayload payload) {
        var hookOpt = hookRegistry.getHook(payload.interactionToken());
        if (hookOpt.isEmpty()) {
            log.warn("No hook registered for interactionToken [responseType={}]",
                    payload.responseType());
            return DispatchResult.failure("no hook found for the provided interactionToken");
        }

        InteractionHook hook = hookOpt.get();
        hookRegistry.remove(payload.interactionToken());
        String content = payload.content() != null ? payload.content() : "";

        try {
            return switch (payload.responseType()) {
                case "REPLY", "DEFERRED_REPLY" -> sendMessage(hook, content, false);
                case "EPHEMERAL_REPLY" -> sendMessage(hook, content, true);
                case "UPDATE_MESSAGE", "DEFERRED_UPDATE" -> editOriginal(hook, content);
                default -> DispatchResult.failure("unmapped responseType: " + payload.responseType());
            };
        } catch (Exception e) {
            log.error("Discord dispatch failed [responseType={}]", payload.responseType(), e);
            return DispatchResult.failure(e.getMessage());
        }
    }

    private DispatchResult sendMessage(InteractionHook hook, String content, boolean ephemeral) {
        var action = hook.sendMessage(content);
        if (ephemeral) {
            action.setEphemeral(true);
        }
        action.queue(
                msg -> log.debug("Message sent [messageId={}]", msg.getId()),
                err -> log.error("Failed to send Discord message", err)
        );
        return DispatchResult.success(null, null);
    }

    private DispatchResult editOriginal(InteractionHook hook, String content) {
        hook.editOriginal(content).queue(
                msg -> log.debug("Message edited [messageId={}]", msg.getId()),
                err -> log.error("Failed to edit Discord message", err)
        );
        return DispatchResult.success(null, null);
    }
}
