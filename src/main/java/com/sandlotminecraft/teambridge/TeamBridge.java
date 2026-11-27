package com.sandlotminecraft.teambridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
 */
public final class TeamBridge extends JavaPlugin implements Listener {

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

        Map<UUID, String> online = new LinkedHashMap<>();
        for (Player player : getServer().getOnlinePlayers()) {
            online.put(player.getUniqueId(), player.getName());
        }
        events.enable(online);
        getLogger().info("enabled; links carry server=" + server);
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
