package com.discord.gateway.startup;

import com.discord.gateway.audit.GuildLifecycleService;
import com.discord.gateway.domain.GuildParam;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.Executor;

@Service
public class StartupReconciliationService extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciliationService.class);

    private final GuildLifecycleService guildLifecycleService;
    private final Executor executor;

    @Autowired
    public StartupReconciliationService(GuildLifecycleService guildLifecycleService) {
        this(guildLifecycleService, task -> Thread.ofVirtual().name("startup-reconciliation").start(task));
    }

    StartupReconciliationService(GuildLifecycleService guildLifecycleService, Executor executor) {
        this.guildLifecycleService = guildLifecycleService;
        this.executor = executor;
    }

    @Override
    public void onReady(ReadyEvent event) {
        var jda = event.getJDA();
        registerConfigCommand(jda);
        executor.execute(() -> reconcileGuilds(jda));
    }

    private void reconcileGuilds(JDA jda) {
        var guilds = jda.getGuilds();
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

    private void registerConfigCommand(JDA jda) {
        var choices = Arrays.stream(GuildParam.values())
                .map(p -> new Command.Choice(p.name(), p.name()))
                .toList();

        jda.upsertCommand(Commands.slash("config", "Configura parâmetros do gateway para esta guilda")
                .addOptions(
                        new OptionData(OptionType.STRING, "parameter", "Parâmetro a configurar", true)
                                .addChoices(choices),
                        new OptionData(OptionType.STRING, "value", "Novo valor do parâmetro", true)
                )).queue(
                cmd -> log.info("Registered /config command [id={}]", cmd.getId()),
                err -> log.error("Failed to register /config command", err)
        );
    }
}
