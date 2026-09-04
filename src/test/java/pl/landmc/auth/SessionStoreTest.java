package pl.landmc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.landmc.auth.session.SessionStore;

/**
 * The one place a password is deliberately not asked for, which makes every rule about when a
 * session does <em>not</em> apply worth a test of its own.
 */
class SessionStoreTest {

    private static final String ADDRESS = "203.0.113.7";

    private final SessionStore sessions = new SessionStore();

    @Test
    @DisplayName("a session works from the address that created it and nowhere else")
    void isBoundToItsAddress() {
        this.sessions.remember("Crispi", ADDRESS, Duration.ofMinutes(15));

        assertTrue(this.sessions.isValid("Crispi", ADDRESS));
        assertFalse(this.sessions.isValid("Crispi", "203.0.113.8"));
    }

    @Test
    @DisplayName("the name is matched however it is capitalised")
    void ignoresCapitalisation() {
        this.sessions.remember("Crispi", ADDRESS, Duration.ofMinutes(15));

        assertTrue(this.sessions.isValid("cRiSpI", ADDRESS));
    }

    @Test
    @DisplayName("somebody else's name does not get in on this session")
    void isBoundToItsName() {
        this.sessions.remember("Crispi", ADDRESS, Duration.ofMinutes(15));

        assertFalse(this.sessions.isValid("KtosInny", ADDRESS));
    }

    @Test
    @DisplayName("an expired session stops working and is dropped as it is read")
    void expires() {
        this.sessions.remember("Crispi", ADDRESS, Duration.ofMillis(-1));
        assertFalse(this.sessions.isValid("Crispi", ADDRESS));

        this.sessions.remember("Crispi", ADDRESS, Duration.ZERO);
        assertFalse(this.sessions.isValid("Crispi", ADDRESS));

        // Nothing sweeps this map; reading is what clears it, so a zero-length session must not
        // be left behind as an entry that can never match.
        assertEquals(0, this.sessions.size());
    }

    @Test
    @DisplayName("forgetting a session takes effect immediately")
    void canBeForgotten() {
        this.sessions.remember("Crispi", ADDRESS, Duration.ofMinutes(15));
        this.sessions.forget("CRISPI");

        assertFalse(this.sessions.isValid("Crispi", ADDRESS));
        assertEquals(0, this.sessions.size());
    }

    @Test
    @DisplayName("a second login from a new address replaces the session rather than adding one")
    void keepsOneSessionPerName() {
        this.sessions.remember("Crispi", ADDRESS, Duration.ofMinutes(15));
        this.sessions.remember("Crispi", "203.0.113.8", Duration.ofMinutes(15));

        assertEquals(1, this.sessions.size());
        assertFalse(this.sessions.isValid("Crispi", ADDRESS), "the old address still had a session");
        assertTrue(this.sessions.isValid("Crispi", "203.0.113.8"));
    }
}
