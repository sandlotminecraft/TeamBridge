package com.sandlotminecraft.teambridge;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Adds {@code team:<name>} completions to GriefPrevention's trust commands (spec §5.4, amendment
 * bridge-2026-11-26-2).
 *
 * <p>Completions are added only when the argument being completed starts with {@code team:}. On any
 * other argument nothing is added and the event is left unhandled: on Paper 26.3 the event counts as
 * handled as soon as it holds one completion, and the server then skips the command's own
 * suggestions (the player names).
 *
 * <p>Runs off the main thread. It reads only {@link Directory#snapshot()}, never another
 * {@code Directory} method, and makes no Parties or LuckPerms call.
 */
public final class TeamTabCompleter implements Listener {

    static final Set<String> COMMANDS = Set.of("trust", "containertrust", "accesstrust", "permissiontrust", "untrust");

    private static final String PREFIX = "team:";
    // The colour codes Directory.idOf strips, so a name matches here as it resolves there.
    private static final Pattern COLOUR = Pattern.compile("(?i)[§&][0-9a-fk-orx]");

    private final Directory directory;

    public TeamTabCompleter(Directory directory) {
        this.directory = directory;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (!event.isCommand()) {
            return;
        }
        List<String> add = completions(event.getBuffer(), directory.snapshot());
        if (add.isEmpty()) {
            return;
        }
        List<String> all = new ArrayList<>(event.getCompletions());
        all.addAll(add);
        event.setCompletions(all);
    }

    /**
     * The {@code team:<name>} completions for a command line, from the given names. Empty unless the
     * command is a trust command and its last argument starts with {@code team:} (any case).
     */
    static List<String> completions(String buffer, List<String> names) {
        String line = buffer.startsWith("/") ? buffer.substring(1) : buffer;
        int space = line.indexOf(' ');
        if (space < 0 || !COMMANDS.contains(line.substring(0, space).toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        String argument = line.substring(line.lastIndexOf(' ') + 1);
        if (!argument.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return List.of();
        }
        String typed = argument.substring(PREFIX.length()).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (normalize(name).startsWith(typed)) {
                out.add(PREFIX + name);
            }
        }
        return out;
    }

    private static String normalize(String name) {
        return COLOUR.matcher(name).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }
}
