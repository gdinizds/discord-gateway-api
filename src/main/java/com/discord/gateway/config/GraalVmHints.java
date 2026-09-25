package com.discord.gateway.config;

import com.discord.gateway.domain.BotCommand;
import com.discord.gateway.domain.BotCommandLog;
import com.discord.gateway.domain.CommandEventType;
import com.discord.gateway.domain.CommandPrefix;
import com.discord.gateway.domain.GuildConfig;
import com.discord.gateway.domain.GuildEventLog;
import com.discord.gateway.domain.GuildParam;
import com.discord.gateway.domain.GuildRegistry;
import com.discord.gateway.domain.GuildStatus;
import com.discord.gateway.domain.MessageDirection;
import com.discord.gateway.domain.MessageLog;
import com.discord.gateway.model.AttachmentRelayException;
import com.discord.gateway.model.BotCommandPayload;
import com.discord.gateway.model.DiscordEventPayload;
import com.discord.gateway.model.DispatchResult;
import com.discord.gateway.model.GuildInfo;
import com.discord.gateway.model.OutboundAttachment;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.model.PayloadValidationException;
import com.discord.gateway.model.ReferencedMessageInfo;
import com.discord.gateway.model.UserInfo;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.List;

public class GraalVmHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        List<Class<?>> types = List.of(
                DiscordEventPayload.class,
                OutboundResponsePayload.class,
                BotCommandPayload.class,
                GuildInfo.class,
                UserInfo.class,
                ReferencedMessageInfo.class,
                OutboundAttachment.class,
                DispatchResult.class,
                AttachmentRelayException.class,
                PayloadValidationException.class,
                BotCommand.class,
                BotCommandLog.class,
                GuildConfig.class,
                GuildEventLog.class,
                GuildRegistry.class,
                MessageLog.class,
                CommandEventType.class,
                CommandPrefix.class,
                GuildParam.class,
                GuildStatus.class,
                MessageDirection.class
        );

        for (Class<?> type : types) {
            hints.reflection().registerType(type,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS);
        }

        hints.resources().registerPattern("db/migration/*/*.sql");
    }
}
