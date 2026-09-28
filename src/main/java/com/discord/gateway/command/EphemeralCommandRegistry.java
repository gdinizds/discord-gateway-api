package com.discord.gateway.command;

import com.discord.gateway.domain.CommandPrefix;
import com.discord.gateway.repository.BotCommandRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory set of slash commands that must be deferred ephemerally.
 * <p>
 * Loaded once from the database before JDA starts (the listener depends on this bean,
 * and the JDA bean depends on the listener), then kept current by {@link BotCommandConsumer}.
 * Lookups stay off the database, like the channel filter cache.
 */
@Component
public class EphemeralCommandRegistry {

    static final String GLOBAL = "GLOBAL";

    private static final Logger log = LoggerFactory.getLogger(EphemeralCommandRegistry.class);

    private final BotCommandRepository repository;
    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    public EphemeralCommandRegistry(BotCommandRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void load() {
        try {
            var commands = repository.findByPrefixAndDeletedFalseAndEphemeralTrue(CommandPrefix.SLASH);
            commands.forEach(c -> keys.add(key(c.getGuildId(), c.getName())));
            log.info("Ephemeral commands loaded [count={}]", keys.size());
        } catch (Exception e) {
            log.warn("Failed to load ephemeral commands, defaulting to public defer until re-registered", e);
        }
    }

    public void apply(String guildId, String name, boolean ephemeral, boolean deleted) {
        String key = key(guildId, name);
        if (ephemeral && !deleted) {
            keys.add(key);
        } else {
            keys.remove(key);
        }
    }

    /** A guild-specific registration wins; otherwise the global registration of the same name applies. */
    public boolean isEphemeral(String guildId, String name) {
        return keys.contains(key(guildId, name)) || keys.contains(key(GLOBAL, name));
    }

    private static String key(String guildId, String name) {
        String scope = (guildId == null || guildId.isBlank()) ? GLOBAL : guildId;
        return scope + ":" + name;
    }
}
