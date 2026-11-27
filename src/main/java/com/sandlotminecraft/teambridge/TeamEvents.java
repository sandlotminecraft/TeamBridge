package com.sandlotminecraft.teambridge;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where the Parties event handlers hand off to, through one {@code runTask} each (I-1), and the
 * enable-time fill. Applies each event to the {@link Directory} and the {@link Membership}.
 *
 * <p>Every method runs on the main thread, except {@link #loadAllAsync}, which runs only from
 * {@code runTaskAsynchronously}.
 */
public final class TeamEvents {

    private final Directory directory;
    private final Membership membership;
    private final TeamSource source;
    private final Scheduler scheduler;
    private final Logger log;

    public TeamEvents(Directory directory, Membership membership, TeamSource source, Scheduler scheduler,
                      Logger log) {
        this.directory = directory;
        this.membership = membership;
        this.source = source;
        this.scheduler = scheduler;
        this.log = log;
    }

    /** PostCreate. The creator, when there is one, is linked. */
    public void created(UUID id, String name, UUID creator) {
        if (directory.put(id, name)) {
            log.info("team create " + id + " " + Directory.displayName(id, name));
        } else {
            log.info("team create " + id + " " + Directory.displayName(id, name) + " ignored: already deleted");
        }
        if (creator != null) {
            membership.joined(creator, id, "create");
        }
    }

    /** PostRename. */
    public void renamed(UUID id, String name) {
        if (directory.rename(id, name)) {
            log.info("team rename " + id + " " + Directory.displayName(id, name));
        }
    }

    /** PostDelete. */
    public void deleted(UUID id, String name) {
        directory.delete(id);
        membership.deleted(id);
        log.info("team delete " + id + " " + Directory.displayName(id, name));
    }

    /** PostJoin. */
    public void joined(UUID player, UUID id, String name) {
        directory.fill(id, name);
        membership.joined(player, id, "join");
    }

    /** PostLeave, including a kick. */
    public void left(UUID player, UUID id, boolean kicked) {
        membership.left(player, id, kicked ? "kick" : "leave");
    }

    /** Enable: fill the directory from every party, off main, and log in the players already online. */
    public void enable(Map<UUID, String> online) {
        scheduler.runTaskAsynchronously(() -> loadAllAsync());
        online.forEach(membership::login);
    }

    /** Off the main thread only. */
    void loadAllAsync() {
        List<TeamRef> all;
        try {
            all = source.allTeamsAsync();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not list parties; the directory fills from logins instead", e);
            return;
        }
        scheduler.runTask(() -> loaded(all));
    }

    void loaded(List<TeamRef> all) {
        for (TeamRef ref : all) {
            directory.fill(ref.id(), ref.name());
        }
        log.info("directory filled: " + directory.size() + " parties");
    }
}
