package com.sandlotminecraft.teambridge;

import com.alessiodp.parties.api.Parties;
import com.alessiodp.parties.api.events.bukkit.party.BukkitPartiesPartyPostCreateEvent;
import com.alessiodp.parties.api.events.bukkit.party.BukkitPartiesPartyPostDeleteEvent;
import com.alessiodp.parties.api.events.bukkit.party.BukkitPartiesPartyPostRenameEvent;
import com.alessiodp.parties.api.events.bukkit.player.BukkitPartiesPlayerPostJoinEvent;
import com.alessiodp.parties.api.events.bukkit.player.BukkitPartiesPlayerPostLeaveEvent;
import com.alessiodp.parties.api.interfaces.PartiesAPI;
import com.alessiodp.parties.api.interfaces.Party;
import com.alessiodp.parties.api.interfaces.PartyPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * The only class that touches the Parties API (I-2).
 *
 * <p>Lookups: every Parties API call sits in a method whose name ends {@code Async}, invoked only
 * from {@code runTaskAsynchronously}. A cache miss there waits on Parties' database pool, which is
 * harmless off the main thread.
 *
 * <p>Events (I-1): Post events fire on Parties' database pool, or for PostDelete on its cached pool.
 * Each handler only copies ids and names off the event and hands them to main with one
 * {@code runTask}.
 */
public final class PartiesTeamSource implements TeamSource {

    private final Scheduler scheduler;

    public PartiesTeamSource(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** The Post event handlers, delivering to {@code events}. Register the result with Bukkit. */
    public Listener listener(TeamEvents events) {
        return new Handlers(events);
    }

    @Override
    public Optional<TeamRef> teamOfAsync(UUID player) {
        PartiesAPI api = Parties.getApi();
        PartyPlayer partyPlayer = api.getPartyPlayer(player);
        if (partyPlayer == null || partyPlayer.getPartyId() == null) {
            return Optional.empty();
        }
        Party party = api.getParty(partyPlayer.getPartyId());
        return party == null ? Optional.empty() : Optional.of(new TeamRef(party.getId(), party.getName()));
    }

    @Override
    public List<TeamRef> allTeamsAsync() {
        PartiesAPI api = Parties.getApi();
        List<TeamRef> all = new ArrayList<>();
        for (Party party : api.getPartiesListByName(Integer.MAX_VALUE, 0)) {
            all.add(new TeamRef(party.getId(), party.getName()));
        }
        return all;
    }

    private final class Handlers implements Listener {

        private final TeamEvents events;

        Handlers(TeamEvents events) {
            this.events = events;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onCreate(BukkitPartiesPartyPostCreateEvent event) {
            UUID id = event.getParty().getId();
            String name = event.getParty().getName();
            UUID creator = event.getCreator() == null ? null : event.getCreator().getPlayerUUID();
            scheduler.runTask(() -> events.created(id, name, creator));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRename(BukkitPartiesPartyPostRenameEvent event) {
            UUID id = event.getParty().getId();
            String name = event.getNewPartyName();
            scheduler.runTask(() -> events.renamed(id, name));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDelete(BukkitPartiesPartyPostDeleteEvent event) {
            UUID id = event.getParty().getId();
            String name = event.getParty().getName();
            scheduler.runTask(() -> events.deleted(id, name));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(BukkitPartiesPlayerPostJoinEvent event) {
            UUID player = event.getPartyPlayer().getPlayerUUID();
            UUID id = event.getParty().getId();
            String name = event.getParty().getName();
            scheduler.runTask(() -> events.joined(player, id, name));
        }

        // isKicked() is deprecated in favour of getCause(), but it is a plain getter (KICK or BAN),
        // and getCause() would bring a Parties enum across to main.
        @SuppressWarnings("deprecation")
        @EventHandler(priority = EventPriority.MONITOR)
        public void onLeave(BukkitPartiesPlayerPostLeaveEvent event) {
            UUID player = event.getPartyPlayer().getPlayerUUID();
            UUID id = event.getParty().getId();
            boolean kicked = event.isKicked();
            scheduler.runTask(() -> events.left(player, id, kicked));
        }
    }
}
