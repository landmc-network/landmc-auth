package pl.landmc.auth.session;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import pl.landmc.auth.Account;

/**
 * Remembers that a player logged in recently from a particular address, so a reconnect a minute
 * later does not ask for the password again.
 *
 * <p>Held in memory rather than in the database or in Redis, deliberately. A session is worth
 * exactly one skipped password prompt; losing them all when the proxy restarts costs every
 * player one extra login, and a restart disconnects everybody anyway. Persisting them would
 * mean a stolen session outliving the process that issued it, which is a strictly worse trade.
 *
 * <p>A session is bound to the address it was created from. That is what makes it a
 * convenience rather than a hole: somebody who knows the name still has to be on the same
 * connection to use it.
 */
public final class SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Records a session for a player who has just disconnected. */
    public void remember(String name, String address, Duration validFor) {
        Objects.requireNonNull(address, "address");

        if (validFor.isZero() || validFor.isNegative()) {
            return;
        }

        this.sessions.put(
                Account.key(name),
                new Session(address, System.currentTimeMillis() + validFor.toMillis()));
    }

    /**
     * Whether this player may skip the password.
     *
     * <p>An expired session is removed as it is read, which keeps the map from growing with
     * everybody who ever played without needing a sweep of its own.
     */
    public boolean isValid(String name, String address) {
        String key = Account.key(name);

        Session session = this.sessions.get(key);
        if (session == null) {
            return false;
        }

        if (System.currentTimeMillis() >= session.expiresAt()) {
            this.sessions.remove(key, session);
            return false;
        }

        return session.address().equals(address);
    }

    /**
     * Drops a player's session.
     *
     * <p>Called when a password changes and when a player logs out on purpose: both are a
     * statement that whatever was trusted before should not be trusted now.
     */
    public void forget(String name) {
        this.sessions.remove(Account.key(name));
    }

    public void clear() {
        this.sessions.clear();
    }

    /** Visible for tests. */
    public int size() {
        return this.sessions.size();
    }

    private record Session(String address, long expiresAt) {
    }
}
