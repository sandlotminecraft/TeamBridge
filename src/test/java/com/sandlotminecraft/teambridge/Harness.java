package com.sandlotminecraft.teambridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The core wired to fakes: a fake scheduler with a fake main thread, a fake {@link TeamSource}
 * that throws when called on that main thread (I-2), and a fake LuckPerms user port.
 */
final class Harness {

    static final String SERVER = "hard";

    /** Queues tasks; the test decides when, and in which order, they run. */
    static final class FakeScheduler implements Scheduler {
        final Deque<Runnable> main = new ArrayDeque<>();
        final Deque<Runnable> async = new ArrayDeque<>();
        boolean onMain;

        @Override
        public void runTask(Runnable task) {
            main.add(task);
        }

        @Override
        public void runTaskAsynchronously(Runnable task) {
            async.add(task);
        }

        /** Runs code as the main thread would. */
        void onMain(Runnable code) {
            boolean was = onMain;
            onMain = true;
            try {
                code.run();
            } finally {
                onMain = was;
            }
        }

        /** Runs code off the main thread. */
        void offMain(Runnable code) {
            boolean was = onMain;
            onMain = false;
            try {
                code.run();
            } finally {
                onMain = was;
            }
        }

        void runNextAsync() {
            offMain(async.removeFirst());
        }

        void runNextMain() {
            onMain(main.removeFirst());
        }

        Runnable takeLastMain() {
            return main.removeLast();
        }

        /** Runs everything queued, async first, until both queues are empty. */
        void drain() {
            while (!async.isEmpty() || !main.isEmpty()) {
                while (!async.isEmpty()) {
                    runNextAsync();
                }
                if (!main.isEmpty()) {
                    runNextMain();
                }
            }
        }
    }

    /** Parties as seen through {@link TeamSource}. Throws on the fake main thread. */
    static final class FakeSource implements TeamSource {
        private final FakeScheduler scheduler;
        final Map<UUID, TeamRef> teamOf = new HashMap<>();
        final List<TeamRef> all = new ArrayList<>();
        boolean failAll;
        boolean failLookup;
        int calls;

        FakeSource(FakeScheduler scheduler) {
            this.scheduler = scheduler;
        }

        private void offMainOnly() {
            if (scheduler.onMain) {
                throw new IllegalStateException("Parties call on the main thread");
            }
            calls++;
        }

        @Override
        public Optional<TeamRef> teamOfAsync(UUID player) {
            offMainOnly();
            if (failLookup) {
                throw new IllegalStateException("lookup failed");
            }
            return Optional.ofNullable(teamOf.get(player));
        }

        @Override
        public List<TeamRef> allTeamsAsync() {
            offMainOnly();
            if (failAll) {
                throw new IllegalStateException("party without a name");
            }
            return List.copyOf(all);
        }
    }

    /** The LuckPerms user port: per player, the set of transient node descriptions it holds. */
    static final class FakeLinks implements UserLinks {
        final Set<UUID> loaded = new HashSet<>();
        final Map<UUID, Set<String>> nodes = new HashMap<>();
        boolean addCalledOffMain;
        private final FakeScheduler scheduler;

        FakeLinks(FakeScheduler scheduler) {
            this.scheduler = scheduler;
        }

        static String node(UUID partyId, String server) {
            return Membership.NODE_PREFIX + partyId + " server=" + server;
        }

        @Override
        public boolean add(UUID player, UUID partyId, String server) {
            addCalledOffMain |= !scheduler.onMain;
            if (!loaded.contains(player)) {
                return false;
            }
            nodes.computeIfAbsent(player, k -> new HashSet<>()).add(node(partyId, server));
            return true;
        }

        @Override
        public boolean remove(UUID player, UUID partyId, String server) {
            addCalledOffMain |= !scheduler.onMain;
            if (!loaded.contains(player)) {
                return false;
            }
            nodes.getOrDefault(player, new HashSet<>()).remove(node(partyId, server));
            return true;
        }

        Set<String> of(UUID player) {
            return nodes.getOrDefault(player, Set.of());
        }
    }

    final List<String> logs = new ArrayList<>();
    final Logger log = Logger.getAnonymousLogger();
    final FakeScheduler scheduler = new FakeScheduler();
    final FakeSource source = new FakeSource(scheduler);
    final FakeLinks links = new FakeLinks(scheduler);
    final Directory directory;
    final Membership membership;
    final TeamEvents events;

    Harness() {
        log.setUseParentHandlers(false);
        log.setLevel(java.util.logging.Level.ALL);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        directory = new Directory(log);
        membership = new Membership(source, scheduler, directory, links, SERVER, log);
        events = new TeamEvents(directory, membership, source, scheduler, log);
    }

    /** A player logs in and the login lookup has not run yet. */
    void login(UUID player) {
        links.loaded.add(player);
        scheduler.onMain(() -> membership.login(player, name(player)));
    }

    void quit(UUID player) {
        scheduler.onMain(() -> membership.quit(player));
        links.loaded.remove(player);
        links.nodes.remove(player);
    }

    static String name(UUID player) {
        return "p-" + player.toString().substring(0, 4);
    }

    // What the Parties event handlers do, from a Parties thread: copy ids and names, one runTask.

    void emitCreate(UUID id, String name, UUID creator) {
        scheduler.offMain(() -> scheduler.runTask(() -> events.created(id, name, creator)));
    }

    void emitRename(UUID id, String name) {
        scheduler.offMain(() -> scheduler.runTask(() -> events.renamed(id, name)));
    }

    void emitDelete(UUID id, String name) {
        scheduler.offMain(() -> scheduler.runTask(() -> events.deleted(id, name)));
    }

    void emitJoin(UUID player, UUID id, String name) {
        scheduler.offMain(() -> scheduler.runTask(() -> events.joined(player, id, name)));
    }

    void emitLeave(UUID player, UUID id, boolean kicked) {
        scheduler.offMain(() -> scheduler.runTask(() -> events.left(player, id, kicked)));
    }

    /** The link the player holds, checked against both Membership and the user port. */
    Optional<UUID> linkOf(UUID player) {
        return membership.partyOf(player);
    }

    boolean logged(String fragment) {
        return logs.stream().anyMatch(line -> line.contains(fragment));
    }
}
