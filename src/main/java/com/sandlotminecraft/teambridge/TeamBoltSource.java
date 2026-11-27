package com.sandlotminecraft.teambridge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.popcraft.bolt.BoltAPI;
import org.popcraft.bolt.source.PlayerSourceResolver;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTransformer;

/**
 * The Bolt source type {@code team} (spec §5.4). A stored identifier is the canonical party id. Bolt's
 * config needs {@code sources: team: {require-permission: false}}.
 *
 * <p>Threads, in Bolt 1.2.37: {@code transformIdentifier} runs on the command thread, and
 * {@code unTransformIdentifier} from {@code /bolt info} and the trust list, also on the main thread;
 * both read the {@link Directory}. The resolver runs from Bolt's access checks, on main.
 * {@code completions} is called from Bolt's tab completion, whose thread is not established, so it
 * reads only the volatile snapshot (amendment bridge-2026-11-26-3).
 *
 * <p>This class implements Bolt types, so it is loaded only after {@link TeamBridge} has found Bolt.
 */
final class TeamBoltSource implements SourceTransformer, PlayerSourceResolver {

    static final String TYPE = "team";

    private final Directory directory;
    private final Function<UUID, Player> onlinePlayer;

    TeamBoltSource(Directory directory, Function<UUID, Player> onlinePlayer) {
        this.directory = directory;
        this.onlinePlayer = onlinePlayer;
    }

    /** Registers the transformer and the resolver. Returns false, and registers nothing, without the Bolt API. */
    static boolean register(Plugin plugin, Directory directory, Logger log) {
        BoltAPI bolt = plugin.getServer().getServicesManager().load(BoltAPI.class);
        if (bolt == null) {
            log.warning("Bolt API not available; the Bolt team source is not registered");
            return false;
        }
        TeamBoltSource source = new TeamBoltSource(directory, plugin.getServer()::getPlayer);
        bolt.registerSourceTransformer(TYPE, source);
        bolt.registerPlayerSourceResolver(source);
        return true;
    }

    /** The same check as the GriefPrevention provider's {@code isMember}. An offline player gets false. */
    @Override
    public boolean resolve(Source source, UUID player) {
        if (source == null || !TYPE.equals(source.getType())) {
            return false;
        }
        Optional<UUID> id = TeamCommands.canonicalId(source.getIdentifier());
        if (id.isEmpty()) {
            return false;
        }
        Player online = onlinePlayer.apply(player);
        return online != null && TeamCommands.isMember(directory, online, id.get());
    }

    /**
     * A team name, or {@code #<32 hex>}, to the canonical id. Null for an empty, unknown or ambiguous
     * input; Bolt then calls {@link #sendErrorNotFound}.
     */
    @Override
    public CompletableFuture<String> transformIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(
                TeamCommands.idOfInput(directory, identifier).map(UUID::toString).orElse(null));
    }

    @Override
    public void sendErrorNotFound(String identifier, CommandSender sender) {
        sender.sendMessage(TeamCommands.notATeam(identifier));
    }

    /** The id to the team's name, or {@code #<32 hex> (disbanded)}. Any other identifier is shown as it is. */
    @Override
    public String unTransformIdentifier(String identifier) {
        return TeamCommands.canonicalId(identifier)
                .map(id -> TeamCommands.shown(directory, id))
                .orElse(identifier);
    }

    /** The volatile snapshot of the team names; any thread. */
    @Override
    public List<String> completions(CommandSender sender) {
        return directory.snapshot();
    }
}
