package pl.landmc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.landmc.auth.password.PasswordHash;
import pl.landmc.auth.password.PasswordHasher;

/**
 * The hashing, on its own.
 *
 * <p>A low iteration count throughout: these check that the arithmetic is wired up correctly,
 * not that it is slow. What it costs in production is a configuration value, and running the
 * real one here would add a minute to every build for nothing.
 */
class PasswordHasherTest {

    private static final int FAST = 1_000;

    private final PasswordHasher hasher = new PasswordHasher(FAST);

    @Test
    @DisplayName("the password that was hashed verifies, and nothing else does")
    void verifiesTheRightPassword() {
        PasswordHash hash = this.hasher.hash("tajneHaslo123".toCharArray());

        assertTrue(this.hasher.verify("tajneHaslo123".toCharArray(), hash));
        assertFalse(this.hasher.verify("tajneHaslo124".toCharArray(), hash));
        assertFalse(this.hasher.verify("".toCharArray(), hash));
        assertFalse(this.hasher.verify("TAJNEHASLO123".toCharArray(), hash));
    }

    @Test
    @DisplayName("the same password hashed twice gives two different values")
    void saltsEveryHash() {
        PasswordHash first = this.hasher.hash("identyczne".toCharArray());
        PasswordHash second = this.hasher.hash("identyczne".toCharArray());

        // Without this, one leaked table shows at a glance which accounts share a password.
        assertNotEquals(first.serialize(), second.serialize());
        assertTrue(this.hasher.verify("identyczne".toCharArray(), first));
        assertTrue(this.hasher.verify("identyczne".toCharArray(), second));
    }

    @Test
    @DisplayName("a stored value survives being written out and read back")
    void roundTripsThroughStorage() {
        PasswordHash original = this.hasher.hash("zażółć gęślą jaźń".toCharArray());

        PasswordHash parsed = PasswordHash.parse(original.serialize());

        assertEquals(original.algorithm(), parsed.algorithm());
        assertEquals(original.iterations(), parsed.iterations());
        assertTrue(this.hasher.verify("zażółć gęślą jaźń".toCharArray(), parsed));
    }

    @Test
    @DisplayName("an account imported from the old server logs in with its old password")
    void verifiesTheLegacyHash() throws Exception {
        // Exactly what the previous server stored: SHA-256 of the UTF-8 bytes, hex, no salt.
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("stareHaslo".getBytes(StandardCharsets.UTF_8));
        PasswordHash imported = PasswordHash.parse("sha256$" + HexFormat.of().formatHex(digest));

        assertTrue(this.hasher.verify("stareHaslo".toCharArray(), imported));
        assertFalse(this.hasher.verify("inneHaslo".toCharArray(), imported));
    }

    @Test
    @DisplayName("an imported hash and an under-hashed one are both marked for replacement")
    void reportsWhatShouldBeRehashed() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("stareHaslo".getBytes(StandardCharsets.UTF_8));

        assertTrue(PasswordHash.parse("sha256$" + HexFormat.of().formatHex(digest)).isOutdated(FAST));
        assertTrue(new PasswordHasher(FAST - 1).hash("x".toCharArray()).isOutdated(FAST));
        assertFalse(this.hasher.hash("x".toCharArray()).isOutdated(FAST));

        // Raising the count later must not invalidate what is already stored.
        assertFalse(this.hasher.hash("x".toCharArray()).isOutdated(FAST - 1));
    }

    @Test
    @DisplayName("junk in the password column is refused rather than read as something else")
    void refusesMalformedStoredValues() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("bez-prefiksu"));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("md5$abcdef"));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("pbkdf2-sha512$1$only-two"));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("pbkdf2-sha512$0$c2FsdA$aGFzaA"));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.parse("sha256$nie-hex"));
    }

    @Test
    @DisplayName("a hash made with one iteration count verifies at that count, not the current one")
    void verifiesAtTheStoredCost() {
        PasswordHash old = new PasswordHasher(500).hash("haslo".toCharArray());

        // The hasher now runs twice as many rounds; the stored value still has to verify, or
        // raising the count would lock every existing account out.
        assertTrue(new PasswordHasher(FAST).verify("haslo".toCharArray(), old));
    }
}
