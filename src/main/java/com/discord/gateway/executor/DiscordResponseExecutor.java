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
            log.warn("Nenhum hook registrado para interactionToken [responseType={}]",
                    payload.responseType());
            return DispatchResult.failure("hook não encontrado para o interactionToken fornecido");
        }

        InteractionHook hook = hookOpt.get();
        String content = payload.content() != null ? payload.content() : "";

        try {
            return switch (payload.responseType()) {
                case "REPLY", "DEFERRED_REPLY" -> sendMessage(hook, content, false);
                case "EPHEMERAL_REPLY" -> sendMessage(hook, content, true);
                case "UPDATE_MESSAGE", "DEFERRED_UPDATE" -> editOriginal(hook, content);
                default -> DispatchResult.failure("responseType não mapeado: " + payload.responseType());
            };
        } catch (Exception e) {
            log.error("Erro ao executar dispatch Discord [responseType={}]", payload.responseType(), e);
            return DispatchResult.failure(e.getMessage());
        }
    }

    private DispatchResult sendMessage(InteractionHook hook, String content, boolean ephemeral) {
        var action = hook.sendMessage(content);
        if (ephemeral) {
            action.setEphemeral(true);
        }
        action.queue(
                msg -> log.debug("Mensagem enviada [messageId={}]", msg.getId()),
                err -> log.error("Erro ao enviar mensagem Discord", err)
        );
        return DispatchResult.success(null, null);
    }

    private DispatchResult editOriginal(InteractionHook hook, String content) {
        hook.editOriginal(content).queue(
                msg -> log.debug("Mensagem editada [messageId={}]", msg.getId()),
                err -> log.error("Erro ao editar mensagem Discord", err)
        );
        return DispatchResult.success(null, null);
    }
}
