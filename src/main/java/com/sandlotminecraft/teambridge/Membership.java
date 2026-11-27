package com.sandlotminecraft.teambridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each online player's link: the transient LuckPerms node {@code sandlot.team.<id>} with context
 * {@code server=<server>} (spec §5.3).
 *
 * <p>Every operation runs on the main thread and is idempotent, except {@link #lookupAsync}, which
 * runs only from {@code runTaskAsynchronously}. Each player event and each login takes a new
 * per-player stamp from one sequence that is never reset; a login lookup is applied only if the
 * player still holds the stamp it was issued with. So a lookup overtaken by an event, or one from
 * an older session that returns after a quit and rejoin, is discarded.
 */
public final class Membership {

    public static final String NODE_PREFIX = "sandlot.team.";

    private final TeamSource source;
    private final Scheduler scheduler;
    private final Directory directory;
    private final UserLinks links;
    private final String server;
    private final Logger log;

    private final Map<UUID, String> online = new HashMap<>();
    private final Map<UUID, Long> stamps = new HashMap<>();
    private final Map<UUID, UUID> linked = new HashMap<>();
    private long sequence;

    public Membership(TeamSource source, Scheduler scheduler, Directory directory, UserLinks links,
                      String server, Logger log) {
        this.source = source;
        this.scheduler = scheduler;
        this.directory = directory;
        this.links = links;
        this.server = server;
        this.log = log;
    }

    /** Replaces any existing link with {@code id}. Does nothing if {@code id} is tombstoned. */
    public void joined(UUID player, UUID id, String cause) {
        if (!online.containsKey(player)) {
            return;
        }
        stamp(player);
        if (directory.isTombstoned(id)) {
            return;
        }
        link(player, id, cause);
    }

    /** Removes the link for {@code id} only. */
    public void left(UUID player, UUID id, String cause) {
        if (!online.containsKey(player)) {
            return;
        }
        stamp(player);
        if (id.equals(linked.get(player))) {
            unlink(player, cause);
        }
    }

    /** Removes {@code id}'s links from every online player. */
    public void deleted(UUID id) {
        for (UUID player : List.copyOf(linked.keySet())) {
            if (id.equals(linked.get(player))) {
                unlink(player, "delete");
            }
        }
    }

    /** The player is online: take a stamp and look the team up off the main thread. */
    public void login(UUID player, String name) {
        online.put(player, name);
        long stamp = stamp(player);
        scheduler.runTaskAsynchronously(() -> lookupAsync(player, stamp));
    }

    /** Off the main thread only. Hands the result back to main with the stamp it was issued with. */
    void lookupAsync(UUID player, long stamp) {
        Optional<TeamRef> team;
        try {
            team = source.teamOfAsync(player);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "login lookup failed for " + player + "; link unchanged", e);
            return;
        }
        scheduler.runTask(() -> lookedUp(player, stamp, team));
    }

    /** Main thread: backfill the directory, then set the link exactly, or none, if the stamp holds. */
    void lookedUp(UUID player, long stamp, Optional<TeamRef> team) {
        team.ifPresent(ref -> directory.fill(ref.id(), ref.name()));
        Long current = stamps.get(player);
        if (current == null || current.longValue() != stamp) {
            log.fine("login lookup for " + player + " discarded (stamp " + stamp + ", now " + current + ")");
            return;
        }
        Optional<UUID> id = team.map(TeamRef::id).filter(found -> !directory.isTombstoned(found));
        if (id.isPresent()) {
            link(player, id.get(), "login");
        } else if (linked.containsKey(player)) {
            unlink(player, "login");
        }
    }

    /** Drops the stamp and the link. */
    public void quit(UUID player) {
        if (linked.containsKey(player)) {
            unlink(player, "quit");
        }
        stamps.remove(player);
        online.remove(player);
    }

    /** Removes all links. */
    public void disable() {
        for (UUID player : List.copyOf(linked.keySet())) {
            unlink(player, "disable");
        }
        stamps.clear();
        online.clear();
    }

    /** The party the player is linked to, if any. */
    public Optional<UUID> partyOf(UUID player) {
        return Optional.ofNullable(linked.get(player));
    }

    private long stamp(UUID player) {
        long stamp = ++sequence;
        stamps.put(player, stamp);
        return stamp;
    }

    private void link(UUID player, UUID id, String cause) {
        UUID old = linked.get(player);
        if (id.equals(old)) {
            return;
        }
        if (old != null) {
            unlink(player, cause);
        }
        if (links.add(player, id, server)) {
            linked.put(player, id);
            log.info("link " + online.get(player) + " " + id + " (" + cause + ")");
        } else {
            log.warning("link " + online.get(player) + " " + id + " not set: no LuckPerms user loaded (" + cause + ")");
        }
    }

    private void unlink(UUID player, String cause) {
        UUID old = linked.remove(player);
        links.remove(player, old, server);
        log.info("unlink " + online.get(player) + " " + old + " (" + cause + ")");
    }
}
