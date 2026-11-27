package com.sandlotminecraft.teambridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Spec 2026-11-25-hard-team-bridge, §5.3 and T-03's acceptance. */
class CoreTest {

    static final UUID A = UUID.fromString("224a6d9c-0000-4000-8000-00000000000a");
    static final UUID B = UUID.fromString("456983c5-0000-4000-8000-00000000000b");
    static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    static final UUID BOB = UUID.fromString("b0b00000-0000-4000-8000-000000000002");

    Harness h;

    @BeforeEach
    void setUp() {
        h = new Harness();
    }

    void assertLinked(UUID player, UUID id) {
        assertEquals(Optional.of(id), h.linkOf(player));
        assertEquals(Set.of(Harness.FakeLinks.node(id, Harness.SERVER)), h.links.of(player));
    }

    void assertUnlinked(UUID player) {
        assertEquals(Optional.empty(), h.linkOf(player));
        assertEquals(Set.of(), h.links.of(player));
    }

    /** Both players online, A created by ALICE, BOB joined: the starting state for most tests. */
    void teamA() {
        h.login(ALICE);
        h.login(BOB);
        h.scheduler.drain();
        h.emitCreate(A, "Alpha", ALICE);
        h.emitJoin(BOB, A, "Alpha");
        h.scheduler.drain();
        assertLinked(ALICE, A);
        assertLinked(BOB, A);
    }

    @Nested
    class Events {

        @Test
        void createPutsEntryAndLinksCreator() {
            h.login(ALICE);
            h.scheduler.drain();
            h.emitCreate(A, "Alpha", ALICE);
            h.scheduler.drain();
            assertEquals(Optional.of("Alpha"), h.directory.nameOf(A));
            assertLinked(ALICE, A);
            assertTrue(h.logged("team create " + A + " Alpha"));
            assertTrue(h.logged("link " + Harness.name(ALICE) + " " + A + " (create)"));
        }

        @Test
        void createWithoutCreatorLinksNobody() {
            h.emitCreate(A, "Alpha", null);
            h.scheduler.drain();
            assertTrue(h.directory.has(A));
        }

        @Test
        void joinLinksAndFillsDirectory() {
            h.login(BOB);
            h.scheduler.drain();
            h.emitJoin(BOB, A, "Alpha");
            h.scheduler.drain();
            assertLinked(BOB, A);
            assertEquals(Optional.of("Alpha"), h.directory.nameOf(A));
        }

        @Test
        void leaveUnlinks() {
            teamA();
            h.emitLeave(BOB, A, false);
            h.scheduler.drain();
            assertUnlinked(BOB);
            assertLinked(ALICE, A);
            assertTrue(h.logged("unlink " + Harness.name(BOB) + " " + A + " (leave)"));
        }

        @Test
        void kickUnlinks() {
            teamA();
            h.emitLeave(BOB, A, true);
            h.scheduler.drain();
            assertUnlinked(BOB);
            assertTrue(h.logged("unlink " + Harness.name(BOB) + " " + A + " (kick)"));
        }

        @Test
        void leaveOfAnotherTeamKeepsTheLink() {
            teamA();
            h.emitLeave(BOB, B, false);
            h.scheduler.drain();
            assertLinked(BOB, A);
        }

        @Test
        void renameReplacesNameAndKeepsLinks() {
            teamA();
            h.emitRename(A, "Beta");
            h.scheduler.drain();
            assertEquals(Optional.of("Beta"), h.directory.nameOf(A));
            assertEquals(Optional.of(A), h.directory.idOf("beta"));
            assertEquals(Optional.empty(), h.directory.idOf("Alpha"));
            assertEquals(List.of("Beta"), h.directory.snapshot());
            assertLinked(ALICE, A);
            assertLinked(BOB, A);
        }

        @Test
        void renameToNullShowsShortHex() {
            teamA();
            h.emitRename(A, null);
            h.scheduler.drain();
            assertEquals(Optional.of("#224a6d9c"), h.directory.nameOf(A));
        }

        @Test
        void renameOfAbsentPartyPutsNothing() {
            h.emitRename(A, "Beta");
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
        }

        @Test
        void deleteRemovesEntryAndAllLinks() {
            teamA();
            h.emitDelete(A, "Alpha");
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
            assertTrue(h.directory.isTombstoned(A));
            assertEquals(List.of(), h.directory.snapshot());
            assertUnlinked(ALICE);
            assertUnlinked(BOB);
            assertTrue(h.logged("team delete " + A + " Alpha"));
        }

        @Test
        void joinOfAnotherTeamReplacesTheLink() {
            teamA();
            h.emitJoin(BOB, B, "Bravo");
            h.scheduler.drain();
            assertLinked(BOB, B);
        }

        @Test
        void joinIsIdempotent() {
            teamA();
            h.emitJoin(BOB, A, "Alpha");
            h.scheduler.drain();
            assertLinked(BOB, A);
        }

        @Test
        void eventsForOfflinePlayersLinkNothing() {
            h.emitCreate(A, "Alpha", ALICE);
            h.emitJoin(BOB, A, "Alpha");
            h.scheduler.drain();
            assertUnlinked(ALICE);
            assertUnlinked(BOB);
            assertTrue(h.directory.has(A));
        }
    }

    /** §5.3 "Event order": both orders of each pair end correctly. */
    @Nested
    class EventOrder {

        @Test
        void createThenDelete() {
            h.login(ALICE);
            h.scheduler.drain();
            h.emitCreate(A, "Alpha", ALICE);
            h.emitDelete(A, "Alpha");
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
            assertUnlinked(ALICE);
        }

        @Test
        void deleteThenCreate() {
            h.login(ALICE);
            h.scheduler.drain();
            h.emitDelete(A, "Alpha");
            h.emitCreate(A, "Alpha", ALICE);
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
            assertEquals(List.of(), h.directory.snapshot());
            assertUnlinked(ALICE);
        }

        @Test
        void renameThenDelete() {
            teamA();
            h.emitRename(A, "Beta");
            h.emitDelete(A, "Beta");
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
            assertUnlinked(ALICE);
            assertUnlinked(BOB);
        }

        @Test
        void deleteThenRename() {
            teamA();
            h.emitDelete(A, "Alpha");
            h.emitRename(A, "Beta");
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
            assertEquals(Optional.empty(), h.directory.idOf("Beta"));
            assertUnlinked(ALICE);
            assertUnlinked(BOB);
        }

        @Test
        void joinThenDelete() {
            h.login(BOB);
            h.scheduler.drain();
            h.emitCreate(A, "Alpha", null);
            h.scheduler.drain();
            h.emitJoin(BOB, A, "Alpha");
            h.emitDelete(A, "Alpha");
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
            assertUnlinked(BOB);
        }

        @Test
        void deleteThenJoin() {
            h.login(BOB);
            h.scheduler.drain();
            h.emitCreate(A, "Alpha", null);
            h.scheduler.drain();
            h.emitDelete(A, "Alpha");
            h.emitJoin(BOB, A, "Alpha");
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
            assertUnlinked(BOB);
        }

        @Test
        void leaveAThenJoinB() {
            teamA();
            h.emitLeave(BOB, A, false);
            h.emitJoin(BOB, B, "Bravo");
            h.scheduler.drain();
            assertLinked(BOB, B);
        }

        @Test
        void joinBThenLeaveA() {
            teamA();
            h.emitJoin(BOB, B, "Bravo");
            h.emitLeave(BOB, A, false);
            h.scheduler.drain();
            assertLinked(BOB, B);
        }
    }

    /** §5.3 "Login" and the stamp (the version guard). */
    @Nested
    class Login {

        @Test
        void loginLinksThePlayersTeamAndBackfillsTheDirectory() {
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.login(ALICE);
            h.scheduler.drain();
            assertLinked(ALICE, A);
            assertEquals(Optional.of("Alpha"), h.directory.nameOf(A));
            assertTrue(h.logged("(login)"));
        }

        @Test
        void loginWithNoTeamLinksNothing() {
            h.login(ALICE);
            h.scheduler.drain();
            assertUnlinked(ALICE);
        }

        @Test
        void staleLoginLookupIsDiscarded() {
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.login(ALICE);
            h.scheduler.runNextAsync();                       // the lookup reads A
            Runnable lookupResult = h.scheduler.takeLastMain();
            h.emitJoin(ALICE, B, "Bravo");                    // an event overtakes it on main
            h.scheduler.drain();
            assertLinked(ALICE, B);
            h.scheduler.onMain(lookupResult);                 // the older lookup lands last
            assertLinked(ALICE, B);
            assertTrue(h.logged("discarded"));
        }

        @Test
        void quitAndRejoinOlderSessionLookupReturningLastIsDiscarded() {
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.login(ALICE);
            h.scheduler.runNextAsync();                       // session 1 reads A
            Runnable session1 = h.scheduler.takeLastMain();
            h.quit(ALICE);
            h.source.teamOf.put(ALICE, new TeamRef(B, "Bravo"));
            h.login(ALICE);
            h.scheduler.drain();                              // session 2 reads B and lands
            assertLinked(ALICE, B);
            h.scheduler.onMain(session1);                     // session 1 lands last
            assertLinked(ALICE, B);
        }

        @Test
        void lookupLandingAfterQuitIsDiscarded() {
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.login(ALICE);
            h.scheduler.runNextAsync();
            Runnable result = h.scheduler.takeLastMain();
            h.quit(ALICE);
            h.links.loaded.add(ALICE);                        // even if LuckPerms still holds the user
            h.scheduler.onMain(result);
            assertUnlinked(ALICE);
        }

        @Test
        void loginLookupSaysNoTeamRemovesAnExistingLink() {
            teamA();
            h.source.teamOf.remove(BOB);
            h.scheduler.onMain(() -> h.membership.login(BOB, Harness.name(BOB)));
            h.scheduler.drain();
            assertUnlinked(BOB);
        }

        @Test
        void loginLookupOfDeletedPartyLinksNothing() {
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.login(ALICE);
            h.scheduler.runNextAsync();                       // reads A before the delete lands
            h.emitDelete(A, "Alpha");
            Runnable delete = h.scheduler.takeLastMain();
            h.scheduler.onMain(delete);
            h.scheduler.drain();
            assertUnlinked(ALICE);
            assertFalse(h.directory.has(A));
        }

        @Test
        void failedLookupLeavesTheLinkUnchanged() {
            teamA();
            h.source.failLookup = true;
            h.scheduler.onMain(() -> h.membership.login(BOB, Harness.name(BOB)));
            h.scheduler.drain();
            assertLinked(BOB, A);
            assertTrue(h.logged("login lookup failed"));
        }

        @Test
        void userNotLoadedInLuckPermsSetsNoLink() {
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.scheduler.onMain(() -> h.membership.login(ALICE, Harness.name(ALICE)));
            h.scheduler.drain();
            assertUnlinked(ALICE);
            assertTrue(h.logged("no LuckPerms user loaded"));
        }

        @Test
        void quitDropsTheLink() {
            teamA();
            h.scheduler.onMain(() -> h.membership.quit(BOB));
            assertUnlinked(BOB);
            h.emitJoin(BOB, B, "Bravo");                      // offline now: nothing to link
            h.scheduler.drain();
            assertUnlinked(BOB);
        }

        @Test
        void disableRemovesAllLinks() {
            teamA();
            h.scheduler.onMain(h.membership::disable);
            assertUnlinked(ALICE);
            assertUnlinked(BOB);
        }
    }

    @Nested
    class Enable {

        @Test
        void enableFillsTheDirectoryAndLinksOnlinePlayers() {
            h.source.all.add(new TeamRef(A, "Alpha"));
            h.source.all.add(new TeamRef(B, "Bravo"));
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.links.loaded.add(ALICE);
            h.scheduler.onMain(() -> h.events.enable(Map.of(ALICE, Harness.name(ALICE))));
            h.scheduler.drain();
            assertEquals(2, h.directory.size());
            assertLinked(ALICE, A);
            assertTrue(h.logged("directory filled: 2 parties"));
        }

        @Test
        void failedAllTeamsThenBackfillFromLogins() {
            h.source.failAll = true;
            h.source.all.add(new TeamRef(A, "Alpha"));
            h.scheduler.onMain(() -> h.events.enable(Map.of()));
            h.scheduler.drain();
            assertEquals(0, h.directory.size());
            assertTrue(h.logged("could not list parties"));

            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.source.teamOf.put(BOB, new TeamRef(B, null));
            h.login(ALICE);
            h.login(BOB);
            h.scheduler.drain();
            assertEquals(Optional.of("Alpha"), h.directory.nameOf(A));
            assertEquals(Optional.of("#456983c5"), h.directory.nameOf(B));
            assertLinked(ALICE, A);
            assertLinked(BOB, B);
        }

        @Test
        void backfillNeverOverwritesAnEventName() {
            teamA();
            h.emitRename(A, "Beta");
            h.scheduler.drain();
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));   // a lookup that read the old name
            h.scheduler.onMain(() -> h.membership.login(ALICE, Harness.name(ALICE)));
            h.scheduler.drain();
            assertEquals(Optional.of("Beta"), h.directory.nameOf(A));
        }

        @Test
        void backfillNeverResurrectsATombstone() {
            teamA();
            h.emitDelete(A, "Alpha");
            h.scheduler.drain();
            h.source.all.add(new TeamRef(A, "Alpha"));
            h.scheduler.onMain(() -> h.events.enable(Map.of()));
            h.scheduler.drain();
            assertFalse(h.directory.has(A));
        }
    }

    @Nested
    class Server {

        @Test
        void linksCarryServerContext() {
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.login(ALICE);
            h.scheduler.drain();
            assertEquals(Set.of("sandlot.team." + A + " server=hard"), h.links.of(ALICE));
        }

        @Test
        void linksCarryTheConfiguredServerName() {
            Harness.FakeScheduler scheduler = new Harness.FakeScheduler();
            Harness.FakeLinks links = new Harness.FakeLinks(scheduler);
            Harness.FakeSource source = new Harness.FakeSource(scheduler);
            Directory directory = new Directory(h.log);
            Membership membership = new Membership(source, scheduler, directory, links, "elsewhere", h.log);
            source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            links.loaded.add(ALICE);
            scheduler.onMain(() -> membership.login(ALICE, "alice"));
            scheduler.drain();
            assertEquals(Set.of("sandlot.team." + A + " server=elsewhere"), links.of(ALICE));
        }
    }

    /** I-2: TeamSource is never called on the main thread. */
    @Nested
    class ThreadRule {

        @Test
        void partiesCallOnFakeMainThrows() {
            assertThrows(IllegalStateException.class,
                    () -> h.scheduler.onMain(() -> h.source.teamOfAsync(ALICE)));
            assertThrows(IllegalStateException.class,
                    () -> h.scheduler.onMain(() -> h.source.allTeamsAsync()));
        }

        @Test
        void loginAndEnableCallTheSourceOffMainOnly() {
            h.source.all.add(new TeamRef(A, "Alpha"));
            h.source.teamOf.put(ALICE, new TeamRef(A, "Alpha"));
            h.links.loaded.add(ALICE);
            h.scheduler.onMain(() -> h.events.enable(Map.of(ALICE, Harness.name(ALICE))));
            h.login(BOB);
            h.scheduler.drain();                              // would throw on a main-thread call
            assertEquals(3, h.source.calls);
            assertFalse(h.links.addCalledOffMain);
            assertLinked(ALICE, A);
        }
    }
}
