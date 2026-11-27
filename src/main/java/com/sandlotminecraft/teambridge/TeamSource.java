package com.sandlotminecraft.teambridge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The thin Parties interface (spec §5.3). Both methods may block on Parties' database pool, so
 * they are only ever invoked from {@code runTaskAsynchronously} (I-2), never on the main thread.
 */
public interface TeamSource {

    /** The party the player is in now, with its name, or empty. */
    Optional<TeamRef> teamOfAsync(UUID player);

    /** Every party, with its name. May throw. */
    List<TeamRef> allTeamsAsync();
}
