package pl.landmc.auth;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A player who is connected but has not proved who they are yet.
 *
 * <p>One of these exists per waiting connection and is read on every command that player types,
 * so it holds the account it was created with rather than looking one up. The account is read
 * once, during the pre-login, and never re-queried while the player waits - a player hammering
 * a wrong password would otherwise be a query per attempt.
 *
 * <p>A null account means the name is not registered, which is a normal state, not an error:
 * that player is being asked to register rather than to log in.
 */
public final class PendingLogin {

    private final UUID playerId;
    private final String name;
    private final @Nullable Account account;
    private final String address;
    private final long deadline;
    private final AtomicInteger attemptsLeft;

    /** Set while a password is being hashed, so a second attempt cannot start in parallel. */
    private volatile boolean checking;

    PendingLogin(
            UUID playerId,
            String name,
            @Nullable Account account,
            String address,
            long deadline,
            int attempts) {

        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.name = Objects.requireNonNull(name, "name");
        this.account = account;
        this.address = Objects.requireNonNull(address, "address");
        this.deadline = deadline;
        this.attemptsLeft = new AtomicInteger(attempts);
    }

    public UUID playerId() {
        return this.playerId;
    }

    public String name() {
        return this.name;
    }

    public @Nullable Account account() {
        return this.account;
    }

    public String address() {
        return this.address;
    }

    /** Whether this player is being asked for a password rather than to choose one. */
    public boolean isRegistered() {
        return this.account != null;
    }

    public boolean hasExpired(long now) {
        return now >= this.deadline;
    }

    public int attemptsLeft() {
        return this.attemptsLeft.get();
    }

    /** Counts a wrong password and reports how many attempts remain. */
    int recordFailure() {
        return this.attemptsLeft.updateAndGet(left -> Math.max(0, left - 1));
    }

    /**
     * Claims the right to check one password.
     *
     * <p>Hashing takes long enough that a player - or something typing on their behalf - can
     * send a second attempt while the first is still running. Without this, ten attempts sent
     * at once would all be checked, all against the same attempt counter, and the limit would
     * mean nothing.
     *
     * @return false when a check is already in flight
     */
    boolean beginCheck() {
        synchronized (this) {
            if (this.checking) {
                return false;
            }
            this.checking = true;
            return true;
        }
    }

    void endCheck() {
        synchronized (this) {
            this.checking = false;
        }
    }
}
