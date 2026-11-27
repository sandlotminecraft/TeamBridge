package com.sandlotminecraft.teambridge;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /caddteam [team]} and {@code /cremoveteam [team]} (spec §5.4): add or remove a team on a Bolt
 * protection, by running {@code bolt modify add|remove normal team <name>} as the player. With no
 * argument the player's own team is used. Several teams per lock work by repeating the command.
 *
 * <p>Also holds the team rules the GriefPrevention provider and the Bolt source share: the
 * {@code #<32 hex>} form of a team id, and membership. They live here, in a class that depends on
 * neither plugin, so either consumer can be loaded without the other.
 *
 * <p>Main thread only: the commands and the shared rules read the {@link Directory} and the
 * {@link Membership}.
 */
public final class TeamCommands implements CommandExecutor {

    /** The no-team line of {@code hard-teams.sk}, verbatim with its double spaces. */
    static final String NO_TEAM = "That didn't work!  You are not a member of a team.  "
            + "Please add a team name for which you would like to change permissions.";

    private static final Pattern HEX_ID = Pattern.compile("#[0-9a-fA-F]{32}");

    private final Directory directory;
    private final Membership membership;

    public TeamCommands(Directory directory, Membership membership) {
        this.directory = directory;
        this.membership = membership;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can change a lock.");
            return true;
        }
        String action = command.getName().equalsIgnoreCase("caddteam") ? "add" : "remove";
        Optional<String> name;
        if (args.length == 0) {
            name = ownTeam(player.getUniqueId()).flatMap(directory::nameOf);
            if (name.isEmpty()) {
                player.sendMessage(NO_TEAM);
                return true;
            }
        } else {
            // Skript's [<text>] took the rest of the line. Not String.join: I-3 bans the token ".join(".
            String requested = Arrays.stream(args).collect(Collectors.joining(" "));
            name = directory.idOf(requested).flatMap(directory::nameOf);
            if (name.isEmpty()) {
                player.sendMessage(notATeam(requested));
                return true;
            }
        }
        player.performCommand("bolt modify " + action + " normal team " + name.get());
        return true;
    }

    /** The player's own team: their link, while the team is in the directory. */
    Optional<UUID> ownTeam(UUID player) {
        return membership.partyOf(player).filter(directory::has);
    }

    static String notATeam(String input) {
        return input + " is not a team.";
    }

    /**
     * {@code #<32 hex>} to the id it names, rebuilt as the canonical dashed, lowercase UUID, so it
     * equals a stored key. Empty for any other input.
     */
    static Optional<UUID> hexId(String input) {
        if (input == null || !HEX_ID.matcher(input).matches()) {
            return Optional.empty();
        }
        String hex = input.substring(1).toLowerCase(Locale.ROOT);
        return Optional.of(UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20)));
    }

    /**
     * A team argument to the id it names: {@code #<32 hex>} gives that id whether or not the team
     * still exists (so a disbanded team's grant can be removed); anything else is a name, matched by
     * {@link Directory#idOf}. Empty for an unknown or ambiguous name.
     */
    static Optional<UUID> idOfInput(Directory directory, String input) {
        return hexId(input).or(() -> directory.idOf(input));
    }

    /** The {@code <32 hex>} form of an id: its canonical string without the dashes. */
    static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    /** A canonical UUID string to its id. Empty for anything else, including non-canonical forms. */
    static Optional<UUID> canonicalId(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equals(text) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The shown form of a team: its name, or {@code #<32 hex> (disbanded)} when it is not in the directory. */
    static String shown(Directory directory, UUID id) {
        return directory.nameOf(id).orElse("#" + hex(id) + " (disbanded)");
    }

    /** Membership, as both consumers check it: the team exists and the player holds its link. */
    static boolean isMember(Directory directory, Player player, UUID id) {
        return directory.has(id) && player.hasPermission(Membership.NODE_PREFIX + id);
    }
}
