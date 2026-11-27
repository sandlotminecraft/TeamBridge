package com.sandlotminecraft.teambridge;

import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.PermissionNode;

/**
 * {@link UserLinks} on LuckPerms (I-4, additive only): the loaded user from
 * {@code getUserManager().getUser(uuid)}, and {@code transientData().add/remove} of one
 * {@code sandlot.team.<id>} permission node with context {@code server=<server>}. Transient data is
 * in memory only, never saved, and LuckPerms clears it at disconnect.
 */
final class LuckPermsLinks implements UserLinks {

    private final LuckPerms luckPerms;

    LuckPermsLinks(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    @Override
    public boolean add(UUID player, UUID partyId, String server) {
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) {
            return false;
        }
        user.transientData().add(node(partyId, server));
        return true;
    }

    @Override
    public boolean remove(UUID player, UUID partyId, String server) {
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) {
            return false;
        }
        user.transientData().remove(node(partyId, server));
        return true;
    }

    private static PermissionNode node(UUID partyId, String server) {
        return PermissionNode.builder(Membership.NODE_PREFIX + partyId)
                .withContext(DefaultContextKeys.SERVER_KEY, server)
                .build();
    }
}
