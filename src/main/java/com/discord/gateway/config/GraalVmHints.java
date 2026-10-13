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
import org.flywaydb.core.extensibility.Plugin;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.ServiceLoader;

public class GraalVmHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        ClassLoader cl = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();

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
            registerClassHierarchy(hints, type);
        }

        hints.reflection().registerType(Plugin.class, MemberCategory.values());
        ServiceLoader.load(Plugin.class, cl).stream().forEach(provider -> {
            Class<?> pluginClass = provider.type();
            registerClassHierarchy(hints, pluginClass);
            for (Field field : pluginClass.getDeclaredFields()) {
                registerClassHierarchy(hints, field.getType());
            }
        });

        String[] packagePatterns = {
                "classpath*:com/discord/gateway/**/*.class",
                "classpath*:org/flywaydb/**/*.class",
                "classpath*:org/hibernate/**/*_$*.class",
                "classpath*:org/hibernate/event/**/*.class",
                "classpath*:org/hibernate/boot/models/**/*.class",
                "classpath*:org/hibernate/annotations/**/*.class",
                "classpath*:org/hibernate/generator/**/*.class",
                "classpath*:org/hibernate/id/**/*.class",
                "classpath*:org/hibernate/persister/**/*.class",
                "classpath*:org/hibernate/metamodel/**/*.class",
                "classpath*:org/hibernate/property/**/*.class",
                "classpath*:org/hibernate/tuple/**/*.class",
                "classpath*:org/hibernate/dialect/**/*.class",
                "classpath*:jakarta/persistence/**/*.class",
                "classpath*:org/jboss/**/*_$*.class",
                "classpath*:org/springframework/cloud/consul/model/**/*.class",
                "classpath*:org/springframework/dao/**/*.class",
                "classpath*:com/ecwid/consul/**/*.class",
                "classpath*:net/jpountz/**/*.class",
                "classpath*:net/dv8tion/jda/**/*.class",
                "classpath*:com/neovisionaries/ws/**/*.class",
                "classpath*:com/fasterxml/uuid/**/*.class",
                "classpath*:io/github/resilience4j/**/*.class",
                "classpath*:org/apache/kafka/common/serialization/**/*.class"
        };
        for (String pattern : packagePatterns) {
            registerClassesByPattern(hints, cl, pattern);
        }

        hints.resources().registerPattern("db/migration/**");
        hints.resources().registerPattern("db/migration/*/*.sql");
        hints.resources().registerPattern("org/flywaydb/core/internal/version.txt");
        hints.resources().registerPattern("org/flywaydb/**/*.sql");
        hints.resources().registerPattern("META-INF/services/org.flywaydb.*");
        hints.resources().registerPattern("org/hibernate/**/*.i18n.properties");
        hints.resources().registerPattern("org/jboss/**/*.i18n.properties");
        hints.resources().registerPattern("net/jpountz/util/**");
    }

    private void registerClassesByPattern(RuntimeHints hints, ClassLoader classLoader, String pattern) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
            SimpleMetadataReaderFactory readerFactory = new SimpleMetadataReaderFactory(classLoader);
            for (Resource resource : resolver.getResources(pattern)) {
                if (!resource.isReadable()) {
                    continue;
                }
                try {
                    MetadataReader reader = readerFactory.getMetadataReader(resource);
                    String className = reader.getClassMetadata().getClassName();
                    Class<?> clazz = Class.forName(className, false, classLoader);
                    registerClassHierarchy(hints, clazz);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void registerClassHierarchy(RuntimeHints hints, Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class && !current.isPrimitive()) {
            hints.reflection().registerType(current, MemberCategory.values());
            hints.reflection().registerType(current.arrayType(), MemberCategory.values());
            for (Class<?> iface : current.getInterfaces()) {
                hints.reflection().registerType(iface, MemberCategory.values());
                hints.reflection().registerType(iface.arrayType(), MemberCategory.values());
            }
            current = current.getSuperclass();
        }
    }
}

