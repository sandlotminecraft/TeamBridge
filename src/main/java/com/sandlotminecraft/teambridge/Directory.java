package com.sandlotminecraft.teambridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Party id to current name, in memory, owned by the main thread (spec §5.3).
 *
 * <p>A deleted id is tombstoned for the life of the process and is never put back: party ids are
 * random UUIDs and are never reused, and a create, rename or join event can arrive after the
 * PostDelete of the same party, because PostDelete runs on another pool.
 *
 * <p>Every method except {@link #snapshot()} is main-thread only.
 */
public final class Directory {

    private static final Pattern COLOUR = Pattern.compile("(?i)[§&][0-9a-fk-orx]");

    private final Logger log;
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> tombstones = new HashSet<>();
    private volatile List<String> snapshot = List.of();

    public Directory(Logger log) {
        this.log = log;
    }

    /** The name to show for a party: its name, or {@code #<first 8 hex of the id>} when it has none. */
    public static String displayName(UUID id, String name) {
        return name != null ? name : "#" + id.toString().substring(0, 8);
    }

    /** Create: puts the entry, unless the id is tombstoned. Returns whether it was put. */
    public boolean put(UUID id, String name) {
        if (tombstones.contains(id)) {
            return false;
        }
        names.put(id, displayName(id, name));
        publish();
        return true;
    }

    /**
     * Join, login lookup and enable: puts the entry only if it is absent and the id is not
     * tombstoned. An existing entry is kept, since create and rename events keep it current.
     */
    public boolean fill(UUID id, String name) {
        if (tombstones.contains(id) || names.containsKey(id)) {
            return false;
        }
        names.put(id, displayName(id, name));
        publish();
        return true;
    }

    /** Rename: replaces the entry, but only if it is present. Returns whether it was replaced. */
    public boolean rename(UUID id, String name) {
        if (!names.containsKey(id)) {
            return false;
        }
        names.put(id, displayName(id, name));
        publish();
        return true;
    }

    /** Delete: removes the entry and tombstones the id. Returns whether an entry was removed. */
    public boolean delete(UUID id) {
        tombstones.add(id);
        boolean removed = names.remove(id) != null;
        if (removed) {
            publish();
        }
        return removed;
    }

    public boolean isTombstoned(UUID id) {
        return tombstones.contains(id);
    }

    public boolean has(UUID id) {
        return names.containsKey(id);
    }

    public Optional<String> nameOf(UUID id) {
        return Optional.ofNullable(names.get(id));
    }

    public List<String> names() {
        return List.copyOf(names.values());
    }

    public int size() {
        return names.size();
    }

    /** The immutable copy of {@link #names()} published after the last change. Any thread. */
    public List<String> snapshot() {
        return snapshot;
    }

    /**
     * The id whose name matches, case-insensitively and after colour stripping. Empty when none
     * matches, and also when more than one does (logged).
     */
    public Optional<UUID> idOf(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = normalize(name);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> matches = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (normalize(entry.getValue()).equals(wanted)) {
                matches.add(entry.getKey());
            }
        }
        if (matches.size() > 1) {
            log.warning("ambiguous team name '" + name + "': " + matches.size() + " parties match " + matches);
            return Optional.empty();
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    private static String normalize(String name) {
        return COLOUR.matcher(name).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }

    private void publish() {
        snapshot = List.copyOf(names.values());
    }
}
