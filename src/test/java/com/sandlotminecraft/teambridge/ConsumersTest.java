package com.sandlotminecraft.teambridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.popcraft.bolt.source.Source;

/**
 * The consumers and commands of spec §5.4 (T-04), on the T-03 harness: the GriefPrevention provider,
 * the Bolt source, the trust-command tab completion, {@code /caddteam}, {@code /cremoveteam},
 * {@code /teambridge audit}, and the class guard on the provider.
 */
class ConsumersTest {

    static final UUID A = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    static final UUID B = UUID.fromString("11111111-2222-4333-8444-555555555555");
    static final UUID GONE = UUID.fromString("99999999-8888-4777-8666-555555555555");
    static final UUID P1 = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    static final UUID P2 = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

    /** The no-team line, typed from hard-teams.sk: two spaces after "work!" and after "team.". */
    static final String NO_TEAM = "That didn't work!  You are not a member of a team.  "
            + "Please add a team name for which you would like to change permissions.";

    Harness h;
    TeamCommands teams;
    Fakes.Record r1;
    Fakes.Record r2;
    Player p1;
    Player p2;

    /** Snowballs (A) and Elves (B) exist; P1 is online and linked to A; P2 is online with no team. */
    @BeforeEach
    void setUp() {
        h = new Harness();
        h.emitCreate(A, "Snowballs", null);
        h.emitCreate(B, "Elves", null);
        h.source.teamOf.put(P1, new TeamRef(A, "Snowballs"));
        h.login(P1);
        h.login(P2);
        h.scheduler.drain();
        assertEquals(Optional.of(A), h.linkOf(P1));
        assertEquals(Optional.empty(), h.linkOf(P2));
        teams = new TeamCommands(h.directory, h.membership);
        r1 = new Fakes.Record();
        r2 = new Fakes.Record();
        p1 = Fakes.linkedPlayer(h, P1, r1);
        p2 = Fakes.linkedPlayer(h, P2, r2);
    }

    static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    /** A second party whose name matches Snowballs after colour stripping and case folding. */
    void makeAmbiguous() {
        h.emitCreate(UUID.fromString("33333333-4444-4555-8666-777777777777"), "§cSNOWBALLS", null);
        h.scheduler.drain();
    }

    @Nested
    class Provider {

        TeamTrusteeProvider provider;

        @BeforeEach
        void setUp() {
            provider = new TeamTrusteeProvider(h.directory, teams);
        }

        @Test
        void prefixIsTeam() {
            assertEquals("team", provider.prefix());
        }

        @Test
        void resolveEmptyIsTheActorsOwnTeam() {
            assertEquals(Optional.of("team:" + A), provider.resolve(p1, ""));
        }

        @Test
        void resolveEmptyWithNoTeamIsEmpty() {
            assertEquals(Optional.empty(), provider.resolve(p2, ""));
            assertEquals(Optional.empty(), provider.resolve(Fakes.console(new Fakes.Record()), ""));
        }

        @Test
        void resolveNameIgnoresCaseAndColourCodes() {
            assertEquals(Optional.of("team:" + A), provider.resolve(p2, "&csNoWbAlLs"));
            assertEquals(Optional.of("team:" + A), provider.resolve(p2, "§lSNOWBALLS"));
            assertEquals(Optional.of("team:" + B), provider.resolve(p1, "elves"));
        }

        @Test
        void resolveHexGivesTheCanonicalDashedId() {
            assertEquals(Optional.of("team:" + A), provider.resolve(p2, "#" + hex(A).toUpperCase(Locale.ROOT)));
            assertEquals(Optional.of("team:0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), provider.resolve(p2, "#" + hex(A)));
        }

        @Test
        void resolveHexOfADisbandedTeamStillGivesItsKey() {
            assertEquals(Optional.of("team:" + GONE), provider.resolve(p2, "#" + hex(GONE)));
        }

        @Test
        void resolveUnknownIsEmpty() {
            assertEquals(Optional.empty(), provider.resolve(p1, "Nobody"));
            assertEquals(Optional.empty(), provider.resolve(p1, "#123"));
        }

        @Test
        void resolveAmbiguousIsEmpty() {
            makeAmbiguous();
            assertEquals(Optional.empty(), provider.resolve(p1, "snowballs"));
        }

        @Test
        void isMemberNeedsTheLinkAndTheTeam() {
            assertTrue(provider.isMember(p1, "team:" + A));
            assertFalse(provider.isMember(p1, "team:" + B));
            assertFalse(provider.isMember(p2, "team:" + A));
            assertFalse(provider.isMember(p1, "team:not-a-uuid"));
            // A player holding the node of a team that is not in the directory is not a member.
            Player stale = Fakes.player(P2, node -> node.equals("sandlot.team." + GONE), new Fakes.Record());
            assertFalse(provider.isMember(stale, "team:" + GONE));
        }

        @Test
        void displayLive() {
            assertEquals(Optional.of("team:Snowballs"), provider.display("team:" + A));
        }

        @Test
        void displayRenamed() {
            h.emitRename(A, "Icicles");
            h.scheduler.drain();
            assertEquals(Optional.of("team:Icicles"), provider.display("team:" + A));
        }

        @Test
        void displayDisbandedIsCopyableForUntrust() {
            h.emitDelete(A, "Snowballs");
            h.scheduler.drain();
            String shown = provider.display("team:" + A).orElseThrow();
            assertEquals("team:#" + hex(A) + " (disbanded)", shown);
            // What a player copies, team:#<32 hex>, resolves back to the stored key.
            String copied = shown.substring(0, shown.indexOf(' '));
            assertEquals(Optional.of("team:" + A), provider.resolve(p1, copied.substring("team:".length())));
        }

        @Test
        void displayOfAForeignKeyIsEmpty() {
            assertEquals(Optional.empty(), provider.display("team:nonsense"));
            assertEquals(Optional.empty(), provider.display("other:" + A));
        }
    }

    @Nested
    class Bolt {

        TeamBoltSource bolt;

        @BeforeEach
        void setUp() {
            bolt = new TeamBoltSource(h.directory, id -> id.equals(P1) ? p1 : id.equals(P2) ? p2 : null);
        }

        String transform(String identifier) {
            return bolt.transformIdentifier(identifier).getNow("not complete");
        }

        @Test
        void transformNameIgnoresCaseAndColourCodes() {
            assertEquals(A.toString(), transform("&csNoWbAlLs"));
            assertEquals(B.toString(), transform("ELVES"));
        }

        @Test
        void transformHexGivesTheCanonicalId() {
            assertEquals(A.toString(), transform("#" + hex(A).toUpperCase(Locale.ROOT)));
            assertEquals(GONE.toString(), transform("#" + hex(GONE)));
        }

        @Test
        void transformUnknownIsNull() {
            assertNull(transform("Nobody"));
        }

        @Test
        void transformAmbiguousIsNull() {
            makeAmbiguous();
            assertNull(transform("Snowballs"));
        }

        @Test
        void transformEmptyIsNull() {
            assertNull(transform(""));
        }

        @Test
        void notFoundSaysNotATeam() {
            Fakes.Record record = new Fakes.Record();
            bolt.sendErrorNotFound("Nobody", Fakes.console(record));
            assertEquals(List.of("Nobody is not a team."), record.messages);
        }

        @Test
        void unTransformLiveRenamedDisbanded() {
            assertEquals("Snowballs", bolt.unTransformIdentifier(A.toString()));
            h.emitRename(A, "Icicles");
            h.scheduler.drain();
            assertEquals("Icicles", bolt.unTransformIdentifier(A.toString()));
            h.emitDelete(A, "Icicles");
            h.scheduler.drain();
            assertEquals("#" + hex(A) + " (disbanded)", bolt.unTransformIdentifier(A.toString()));
        }

        @Test
        void bothWays() {
            assertEquals("Elves", bolt.unTransformIdentifier(transform("elves")));
            assertEquals(A.toString(), transform(bolt.unTransformIdentifier(A.toString())));
            // A disbanded team's shown form, without " (disbanded)", transforms back to its id.
            h.emitDelete(A, "Snowballs");
            h.scheduler.drain();
            String shown = bolt.unTransformIdentifier(A.toString());
            assertEquals(A.toString(), transform(shown.substring(0, shown.indexOf(' '))));
            // And a name that is no team transforms to null, which Bolt reports as not found.
            assertNull(transform("Snowballs"));
        }

        @Test
        void completionsAreTheVolatileSnapshot() {
            assertSame(h.directory.snapshot(), bolt.completions(null));
            assertEquals(2, bolt.completions(null).size());
        }

        @Test
        void resolverMatchesAMemberOnly() {
            assertTrue(bolt.resolve(Source.of("team", A.toString()), P1));
            assertFalse(bolt.resolve(Source.of("team", A.toString()), P2));
            assertFalse(bolt.resolve(Source.of("team", B.toString()), P1));
            assertFalse(bolt.resolve(Source.of("party", A.toString()), P1));
            assertFalse(bolt.resolve(Source.of("team", "Snowballs"), P1));
        }

        @Test
        void resolverOfflinePlayerIsFalse() {
            UUID offline = UUID.randomUUID();
            assertFalse(bolt.resolve(Source.of("team", A.toString()), offline));
        }

        @Test
        void resolverDisbandedTeamIsFalse() {
            h.emitDelete(A, "Snowballs");
            h.scheduler.drain();
            TeamBoltSource stale = new TeamBoltSource(h.directory,
                    id -> Fakes.player(id, node -> node.equals("sandlot.team." + A), new Fakes.Record()));
            assertFalse(stale.resolve(Source.of("team", A.toString()), P1));
        }
    }

    @Nested
    class TabCompletion {

        TeamTabCompleter completer;
        CommandSender sender;

        @BeforeEach
        void setUp() {
            completer = new TeamTabCompleter(h.directory);
            sender = Fakes.console(new Fakes.Record());
        }

        AsyncTabCompleteEvent complete(String buffer) {
            AsyncTabCompleteEvent event = new AsyncTabCompleteEvent(sender, buffer, true, null);
            completer.onTabComplete(event);
            return event;
        }

        @Test
        void nonTeamArgumentAddsNothingAndLeavesTheEventUnhandled() {
            for (String buffer : List.of("/trust ", "/trust Ste", "/untrust sn", "/containertrust t",
                    "/accesstrust team", "/permissiontrust tea:")) {
                AsyncTabCompleteEvent event = complete(buffer);
                assertTrue(event.getCompletions().isEmpty(), buffer);
                assertFalse(event.isHandled(), buffer);
            }
        }

        @Test
        void teamArgumentAddsTheMatchingTeams() {
            AsyncTabCompleteEvent event = complete("/trust team:sn");
            assertEquals(List.of("team:Snowballs"), event.getCompletions());
            assertTrue(event.isHandled());
        }

        @Test
        void teamPrefixAndNameMatchIgnoringCase() {
            assertEquals(List.of("team:Elves"), complete("/untrust TEAM:eL").getCompletions());
            assertEquals(List.of("team:Elves", "team:Snowballs"),
                    complete("/ContainerTrust Team:").getCompletions().stream().sorted().toList());
        }

        @Test
        void everyTrustCommandIsCovered() {
            for (String command : List.of("trust", "containertrust", "accesstrust", "permissiontrust", "untrust")) {
                assertEquals(List.of("team:Elves"), complete("/" + command + " team:e").getCompletions(), command);
            }
        }

        @Test
        void otherCommandsAreLeftAlone() {
            AsyncTabCompleteEvent event = complete("/msg team:sn");
            assertTrue(event.getCompletions().isEmpty());
            assertFalse(event.isHandled());
        }

        @Test
        void teamArgumentWithNoMatchAddsNothing() {
            AsyncTabCompleteEvent event = complete("/trust team:zz");
            assertTrue(event.getCompletions().isEmpty());
            assertFalse(event.isHandled());
        }

        @Test
        void colourCodedNameMatchesAsIdOfDoes() {
            assertEquals(List.of("team:§cRed"), TeamTabCompleter.completions("/trust team:re", List.of("§cRed", "Blue")));
        }
    }

    @Nested
    class Commands {

        boolean run(Player player, String name, String... args) {
            return teams.onCommand(player, Fakes.command(name), name, args);
        }

        @Test
        void addOwnTeam() {
            run(p1, "caddteam");
            assertEquals(List.of("bolt modify add normal team Snowballs"), r1.performed);
            assertEquals(List.of(), r1.messages);
        }

        @Test
        void removeOwnTeam() {
            run(p1, "cremoveteam");
            assertEquals(List.of("bolt modify remove normal team Snowballs"), r1.performed);
        }

        @Test
        void addNamedTeam() {
            run(p2, "caddteam", "eLvEs");
            assertEquals(List.of("bolt modify add normal team Elves"), r2.performed);
        }

        @Test
        void removeNamedTeam() {
            run(p1, "cremoveteam", "Elves");
            assertEquals(List.of("bolt modify remove normal team Elves"), r1.performed);
        }

        @Test
        void noTeamMessageIsTheSkriptLineVerbatim() {
            run(p2, "caddteam");
            run(p2, "cremoveteam");
            assertEquals(List.of(NO_TEAM, NO_TEAM), r2.messages);
            assertEquals(TeamCommands.NO_TEAM, NO_TEAM);
            assertEquals(List.of(), r2.performed);
        }

        @Test
        void unknownNameIsNotATeam() {
            run(p1, "caddteam", "Nobody");
            run(p1, "cremoveteam", "Nobody");
            assertEquals(List.of("Nobody is not a team.", "Nobody is not a team."), r1.messages);
            assertEquals(List.of(), r1.performed);
        }

        @Test
        void ownTeamThatWasDisbandedIsNoTeam() {
            h.emitDelete(A, "Snowballs");
            h.scheduler.drain();
            run(p1, "caddteam");
            assertEquals(List.of(NO_TEAM), r1.messages);
        }

        @Test
        void consoleIsRefused() {
            Fakes.Record record = new Fakes.Record();
            assertTrue(teams.onCommand(Fakes.console(record), Fakes.command("caddteam"), "caddteam", new String[0]));
            assertEquals(1, record.messages.size());
        }
    }

    @Nested
    class Audit {

        Fakes.Record console;
        AdminCommands admin;

        @BeforeEach
        void setUp() {
            console = new Fakes.Record();
            h.source.all.add(new TeamRef(A, "Snowballs"));
            h.source.all.add(new TeamRef(B, "Elves"));
            admin = new AdminCommands(h.source, h.directory, h.membership, h.scheduler, () -> List.of(P1, P2), h.log);
        }

        void audit(String... args) {
            h.scheduler.onMain(() -> admin.onCommand(Fakes.console(console), Fakes.command("teambridge"), "teambridge", args));
            h.scheduler.drain();
        }

        @Test
        void cleanAudit() {
            int before = h.source.calls;
            audit("audit");
            assertEquals(List.of("audit: 2 online, 0 link mismatches, 0 directory mismatches"), console.messages);
            // Two lookups and the party list, all made off the fake main thread (the source throws on it).
            assertEquals(before + 3, h.source.calls);
        }

        @Test
        void mismatchesAreCounted() {
            h.source.teamOf.put(P2, new TeamRef(B, "Elves"));                                // missed join
            h.source.all.set(0, new TeamRef(A, "Icicles"));                                  // missed rename
            h.source.all.add(new TeamRef(UUID.fromString("44444444-4444-4444-8444-444444444444"), "New")); // missed create
            h.emitCreate(GONE, "Ghost", null);                                               // missed delete
            h.scheduler.drain();
            audit("audit");
            assertEquals(List.of("audit: 2 online, 1 link mismatches, 3 directory mismatches"), console.messages);
        }

        @Test
        void auditChangesNothing() {
            h.source.teamOf.put(P2, new TeamRef(B, "Elves"));
            audit("audit");
            assertEquals(Optional.empty(), h.linkOf(P2));
            assertEquals(2, h.directory.size());
        }

        @Test
        void failedPartiesCallIsReported() {
            h.source.failAll = true;
            audit("audit");
            assertEquals(1, console.messages.size());
            assertTrue(console.messages.get(0).startsWith("audit: failed"), console.messages.get(0));
        }

        @Test
        void otherArgumentsGetTheUsage() {
            audit();
            audit("reload");
            assertEquals(List.of(AdminCommands.USAGE, AdminCommands.USAGE), console.messages);
        }
    }

    @Nested
    class Registration {

        final Logger log = Logger.getAnonymousLogger();
        boolean registered;

        @Test
        void providerIsSkippedWhenTheSpiClassIsAbsent() throws Exception {
            try (URLClassLoader stock = new URLClassLoader(new URL[0], null)) {
                assertFalse(TeamBridge.whenPresent(TeamBridge.TRUSTEE_SPI, stock, "provider", log,
                        () -> registered = true));
            }
            assertFalse(registered);
        }

        @Test
        void providerIsRegisteredWhenTheSpiClassIsPresent() {
            assertTrue(TeamBridge.whenPresent(TeamBridge.TRUSTEE_SPI, TeamBridge.class.getClassLoader(), "provider",
                    log, () -> registered = true));
            assertTrue(registered);
        }

        @Test
        void boltSourceIsSkippedWithoutBolt() throws Exception {
            try (URLClassLoader none = new URLClassLoader(new URL[0], null)) {
                assertFalse(TeamBridge.whenPresent(TeamBridge.BOLT_API, none, "bolt", log, () -> registered = true));
            }
            assertFalse(registered);
        }

        @Test
        void theSpiNameIsTheT02Interface() throws Exception {
            assertEquals("me.ryanhamshire.GriefPrevention.trustee.TrusteeProvider",
                    me.ryanhamshire.GriefPrevention.trustee.TrusteeProvider.class.getName());
            assertEquals(TeamBridge.TRUSTEE_SPI, me.ryanhamshire.GriefPrevention.trustee.TrusteeProvider.class.getName());
            assertEquals(TeamBridge.BOLT_API, org.popcraft.bolt.BoltAPI.class.getName());
        }
    }

    @Test
    void sharedRules() {
        assertEquals(Optional.of(A), TeamCommands.hexId("#" + hex(A)));
        assertEquals(Optional.empty(), TeamCommands.hexId(hex(A)));
        assertEquals(Optional.empty(), TeamCommands.hexId("#" + hex(A) + "0"));
        assertEquals(Optional.empty(), TeamCommands.canonicalId("1-1-1-1-1"));
        assertEquals(Optional.of(A), TeamCommands.canonicalId(A.toString()));
    }
}
