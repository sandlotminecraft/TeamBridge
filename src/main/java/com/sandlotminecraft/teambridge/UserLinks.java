package com.sandlotminecraft.teambridge;

import java.util.UUID;

/**
 * The LuckPerms user port: adds or removes one transient {@code sandlot.team.<partyId>} node with
 * context {@code server=<server>} on a loaded user. Main thread, in memory, nothing stored (I-4).
 */
public interface UserLinks {

    /** Returns false when LuckPerms has no loaded user for the player. */
    boolean add(UUID player, UUID partyId, String server);

    /** Returns false when LuckPerms has no loaded user for the player. */
    boolean remove(UUID player, UUID partyId, String server);
}
