package pl.landmc.auth.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Gives every player the same UUID whether they logged in with a password or with Mojang.
 *
 * <p>This is the piece that makes premium login safe to offer at all.
 *
 * <p>A player who joins in offline mode gets a UUID derived from their name. A player Mojang
 * authenticates gets the UUID Mojang issued, which is a completely different value. Every other
 * plugin on this network - punishments, friends, vouchers, islands, the economy - keys on UUID.
 * So without this listener, the moment somebody ran {@code /premium} they would appear to the
 * whole network as a player who had never been here: no rank, no money, no friends, and no ban
 * either. Turning premium off would hand them the old account back, which makes it a way to
 * shed a punishment rather than a convenience.
 *
 * <p>So the offline UUID wins, always. It is derived from the name, the name is unique, and it
 * therefore does not change when the way the player proves who they are changes. The premium
 * profile's properties - the skin and its signature - are kept, so a premium player still looks
 * like themselves; only the id is rewritten.
 *
 * <p>The cost of this choice is that the network can never migrate to Mojang UUIDs without
 * rewriting every table that holds one. That is a real cost, and it is still the cheaper side:
 * the alternative loses a player's data on the day they change how they log in.
 */
public final class GameProfileListener {

    /** The prefix Mojang's own offline-mode derivation uses. Must match Velocity's exactly. */
    private static final String OFFLINE_PREFIX = "OfflinePlayer:";

    /** The UUID a player has when nobody asked Mojang about them. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes((OFFLINE_PREFIX + name).getBytes(StandardCharsets.UTF_8));
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        if (!event.isOnlineMode()) {
            // Velocity already derived this exact id; rewriting it would only risk disagreeing.
            return;
        }

        GameProfile profile = event.getGameProfile();
        UUID stable = offlineUuid(event.getUsername());

        if (profile.getId().equals(stable)) {
            return;
        }

        event.setGameProfile(profile.withId(stable));
    }
}
