package com.sandlotminecraft.teambridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * {@code /teambridge audit} (spec §5.4), for the console, permission {@code teambridge.admin}.
 *
 * <p>Compares each online player's link with a fresh Parties lookup, and the directory with every
 * party, then prints {@code audit: <n> online, <m> link mismatches, <d> directory mismatches}. It is
 * read-only: a missed event heals at the player's next login or the next enable.
 *
 * <p>The command runs on main and hands the Parties calls to {@link #auditAsync}, through
 * {@code runTaskAsynchronously} (I-2); the comparison runs back on main, where the links and the
 * directory live.
 */
public final class AdminCommands implements CommandExecutor {

    static final String USAGE = "Usage: /teambridge audit";

    private final TeamSource source;
    private final Directory directory;
    private final Membership membership;
    private final Scheduler scheduler;
    private final Supplier<List<UUID>> online;
    private final Logger log;

    public AdminCommands(TeamSource source, Directory directory, Membership membership, Scheduler scheduler,
                         Supplier<List<UUID>> online, Logger log) {
        this.source = source;
        this.directory = directory;
        this.membership = membership;
        this.scheduler = scheduler;
        this.online = online;
        this.log = log;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("audit")) {
            sender.sendMessage(USAGE);
            return true;
        }
        audit(sender);
        return true;
    }

    /** Main thread: note who is online, then look them and every party up off main. */
    void audit(CommandSender sender) {
        List<UUID> players = List.copyOf(online.get());
        scheduler.runTaskAsynchronously(() -> auditAsync(sender, players));
    }

    /** Off the main thread only. */
    void auditAsync(CommandSender sender, List<UUID> players) {
        Map<UUID, Optional<UUID>> fresh = new HashMap<>();
        List<TeamRef> all;
        try {
            for (UUID player : players) {
                fresh.put(player, source.teamOfAsync(player).map(TeamRef::id));
            }
            all = source.allTeamsAsync();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "audit failed", e);
            scheduler.runTask(() -> sender.sendMessage("audit: failed, " + e));
            return;
        }
        scheduler.runTask(() -> report(sender, players, fresh, all));
    }

    /** Main thread: compare and print. */
    void report(CommandSender sender, List<UUID> players, Map<UUID, Optional<UUID>> fresh, List<TeamRef> all) {
        int links = 0;
        for (UUID player : players) {
            if (!membership.partyOf(player).equals(fresh.get(player))) {
                links++;
            }
        }
        int entries = 0;
        int matched = 0;
        for (TeamRef ref : all) {
            Optional<String> name = directory.nameOf(ref.id());
            if (name.isEmpty()) {
                entries++;
            } else {
                matched++;
                if (!name.get().equals(Directory.displayName(ref.id(), ref.name()))) {
                    entries++;
                }
            }
        }
        entries += directory.size() - matched;
        sender.sendMessage("audit: " + players.size() + " online, " + links + " link mismatches, "
                + entries + " directory mismatches");
    }
}
