package com.sandlotminecraft.teambridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TeamBridge: team claims and locks by party id, shown by current name (spec 2026-11-25-hard-team-bridge).
 *
 * <p>Wires the core: {@link PartiesTeamSource} (the only Parties caller), {@link Directory} (id to
 * name) and {@link Membership} (each online player's transient {@code sandlot.team.<id>} node).
 * All state is confined to the main thread; Parties is only called off it (spec §5.1).
 *
 * <p>The consumers (spec §5.4): the GriefPrevention trustee provider, registered only when the
 * Sandlot GriefPrevention build's SPI class is present; the Bolt {@code team} source, registered only
 * when Bolt is; the trust-command tab completion; {@code /caddteam}, {@code /cremoveteam} and
 * {@code /teambridge audit}.
 */
public final class TeamBridge extends JavaPlugin implements Listener {

    /** The GriefPrevention trustee provider SPI, present only in the Sandlot GriefPrevention build. */
    static final String TRUSTEE_SPI = "me.ryanhamshire.GriefPrevention.trustee.TrusteeProvider";
    static final String BOLT_API = "org.popcraft.bolt.BoltAPI";

    private Directory directory;
    private Membership membership;

    @Override
    public void onEnable() {
        LuckPerms luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            getLogger().severe("LuckPerms API not available; disabling");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        String server = luckPerms.getServerName();
        Scheduler scheduler = new Scheduler() {
            @Override
            public void runTask(Runnable task) {
                if (isEnabled()) {
                    getServer().getScheduler().runTask(TeamBridge.this, task);
                }
            }

            @Override
            public void runTaskAsynchronously(Runnable task) {
                if (isEnabled()) {
                    getServer().getScheduler().runTaskAsynchronously(TeamBridge.this, task);
                }
            }
        };

        PartiesTeamSource source = new PartiesTeamSource(scheduler);
        directory = new Directory(getLogger());
        membership = new Membership(source, scheduler, directory, new LuckPermsLinks(luckPerms), server, getLogger());
        TeamEvents events = new TeamEvents(directory, membership, source, scheduler, getLogger());

        getServer().getPluginManager().registerEvents(source.listener(events), this);
        getServer().getPluginManager().registerEvents(this, this);

        TeamCommands teams = new TeamCommands(directory, membership);
        getCommand("caddteam").setExecutor(teams);
        getCommand("cremoveteam").setExecutor(teams);
        getCommand("teambridge").setExecutor(new AdminCommands(source, directory, membership, scheduler,
                () -> getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList(), getLogger()));
        getServer().getPluginManager().registerEvents(new TeamTabCompleter(directory), this);
        whenPresent(TRUSTEE_SPI, getClassLoader(), "the GriefPrevention trustee provider", getLogger(),
                () -> TeamTrusteeProvider.register(this, directory, teams));
        whenPresent(BOLT_API, getClassLoader(), "the Bolt team source", getLogger(),
                () -> TeamBoltSource.register(this, directory, getLogger()));

        Map<UUID, String> online = new LinkedHashMap<>();
        for (Player player : getServer().getOnlinePlayers()) {
            online.put(player.getUniqueId(), player.getName());
        }
        events.enable(online);
        getLogger().info("enabled; links carry server=" + server);
    }

    /**
     * Runs {@code register} only if {@code className} can be loaded, and returns its result. Otherwise
     * logs WARN and goes on: the consumer that needs the class is skipped, and the others are
     * unaffected. The consumer's own class is loaded only inside {@code register}, so nothing loads a
     * missing class.
     */
    static boolean whenPresent(String className, ClassLoader loader, String what, Logger log,
                               BooleanSupplier register) {
        try {
            Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            log.warning(className + " not found; " + what + " is not registered");
            return false;
        }
        if (!register.getAsBoolean()) {
            return false;
        }
        log.info(what + " registered");
        return true;
    }

    @Override
    public void onDisable() {
        if (membership != null) {
            membership.disable();
        }
        membership = null;
        directory = null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        membership.login(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        membership.quit(event.getPlayer().getUniqueId());
    }
}
