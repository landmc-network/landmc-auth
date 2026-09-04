package pl.landmc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.landmc.auth.listener.GameProfileListener;

/**
 * The UUID every player keeps for ever.
 *
 * <p>This value is written into punishments, friend lists, vouchers and every economy row on the
 * network. If it ever changes - a different prefix, a different encoding, a stray
 * lower-casing - every one of those rows stops belonging to the player it was written for, and
 * nothing anywhere reports an error. So it is pinned here against literals rather than against
 * the implementation.
 */
class OfflineUuidTest {

    @Test
    @DisplayName("the id is the one Minecraft derives for an offline player")
    void matchesTheKnownValues() {
        // Computed from the documented derivation - MD5 of "OfflinePlayer:<name>", version 3 -
        // and deliberately written out rather than recomputed, so a change to the code cannot
        // quietly change the expectation with it.
        assertEquals(
                UUID.fromString("ff20d2eb-1308-3499-b707-dbd4c70a4ea3"),
                GameProfileListener.offlineUuid("Crispi"));
        assertEquals(
                UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"),
                GameProfileListener.offlineUuid("Notch"));
    }

    @Test
    @DisplayName("the derivation is the same one the JDK and the servers use")
    void agreesWithTheDerivation() {
        for (String name : new String[] {"Crispi", "Notch", "gracz_123", "ŻÓŁĆ"}) {
            assertEquals(
                    UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)),
                    GameProfileListener.offlineUuid(name),
                    name);
        }
    }

    @Test
    @DisplayName("capitalisation produces a different id, which is why accounts are keyed by name")
    void isCaseSensitive() {
        // Minecraft itself treats these as one player, but the derivation does not - which is
        // exactly why the account table is keyed on the lower-cased name and not on this.
        assertNotEquals(
                GameProfileListener.offlineUuid("Crispi"),
                GameProfileListener.offlineUuid("crispi"));
    }
}
