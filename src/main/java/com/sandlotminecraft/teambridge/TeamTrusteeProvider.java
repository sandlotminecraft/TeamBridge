package com.sandlotminecraft.teambridge;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import me.ryanhamshire.GriefPrevention.trustee.TrusteeProvider;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * The GriefPrevention trustee provider for the prefix {@code team} (spec §5.2, §5.4). A stored key is
 * {@code team:<canonical party id>}.
 *
 * <p>GriefPrevention calls every method on the main thread: {@code resolve} and {@code display} from
 * its commands, {@code isMember} from claim checks. They read the {@link Directory} and the
 * {@link Membership} in memory.
 *
 * <p>This class implements a class that only the Sandlot GriefPrevention build has, so it is loaded
 * only after {@link TeamBridge} has found that class.
 */
final class TeamTrusteeProvider implements TrusteeProvider {

    static final String PREFIX = "team";

    private final Directory directory;
    private final TeamCommands teams;

    TeamTrusteeProvider(Directory directory, TeamCommands teams) {
        this.directory = directory;
        this.teams = teams;
    }

    /** Registers the provider with the ServicesManager, where GriefPrevention looks it up at each use. */
    static boolean register(Plugin plugin, Directory directory, TeamCommands teams) {
        plugin.getServer().getServicesManager().register(TrusteeProvider.class,
                new TeamTrusteeProvider(directory, teams), plugin, ServicePriority.Normal);
        return true;
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    /**
     * {@code ""} is the actor's own team; {@code #<32 hex>} is that id, as a canonical key, so a
     * disbanded team's key can be removed; anything else is a team name.
     */
    @Override
    public Optional<String> resolve(CommandSender actor, String input) {
        Optional<UUID> id;
        if (input.isEmpty()) {
            id = actor instanceof Player player ? teams.ownTeam(player.getUniqueId()) : Optional.empty();
        } else {
            id = TeamCommands.idOfInput(directory, input);
        }
        return id.map(TeamTrusteeProvider::key);
    }

    @Override
    public boolean isMember(Player player, String key) {
        return idOf(key).map(id -> TeamCommands.isMember(directory, player, id)).orElse(false);
    }

    /** {@code team:<name>}, or {@code team:#<32 hex> (disbanded)}, which is copyable for untrust. */
    @Override
    public Optional<String> display(String key) {
        return idOf(key).map(id -> PREFIX + ":" + TeamCommands.shown(directory, id));
    }

    static String key(UUID id) {
        return PREFIX + ":" + id;
    }

    /** The id a stored key names. Empty for a key that is not {@code team:<canonical uuid>}. */
    static Optional<UUID> idOf(String key) {
        if (key == null || !key.toLowerCase(Locale.ROOT).startsWith(PREFIX + ":")) {
            return Optional.empty();
        }
        return TeamCommands.canonicalId(key.substring(PREFIX.length() + 1));
    }
}
