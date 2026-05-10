package com.discord.gateway.startup;

import com.discord.gateway.audit.GuildLifecycleService;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StartupReconciliationService extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciliationService.class);

    private final GuildLifecycleService guildLifecycleService;

    public StartupReconciliationService(GuildLifecycleService guildLifecycleService) {
        this.guildLifecycleService = guildLifecycleService;
    }

    @Override
    @Transactional
    public void onReady(ReadyEvent event) {
        var guilds = event.getJDA().getGuilds();
        log.info("Startup reconciliation: {} guilds", guilds.size());
        for (var guild : guilds) {
            try {
                guildLifecycleService.onJoin(guild);
                log.debug("Reconciled guild [id={}, name={}]", guild.getId(), guild.getName());
            } catch (Exception e) {
                log.error("Failed to reconcile guild [id={}]", guild.getId(), e);
            }
        }
        log.info("Startup reconciliation complete");
    }
}
