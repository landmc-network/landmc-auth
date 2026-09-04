package pl.landmc.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pl.landmc.auth.password.PasswordHash;

/**
 * A registered player, as the rest of the plugin sees one.
 *
 * <p>Immutable, because it is read on Netty threads during a login and written on a database
 * worker, and the two must never see half of a change. Every mutation returns a new value.
 *
 * @param name the lower-cased name, which is the account's identity
 * @param displayName the capitalisation the player registered with
 * @param password null when only Mojang authenticates this player
 */
public record Account(
        String name,
        String displayName,
        @Nullable PasswordHash password,
        boolean premium,
        Instant registeredAt,
        @Nullable String registeredIp,
        @Nullable Instant lastLoginAt,
        @Nullable String lastLoginIp) {

    public Account {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(registeredAt, "registeredAt");

        if (!name.equals(name.toLowerCase(Locale.ROOT))) {
            // A name that reached here with capitals would silently become a second account.
            throw new IllegalArgumentException("Account name must be lower case: " + name);
        }
    }

    /** Normalises a typed or connecting name into the form the account table is keyed by. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** A brand new account with a password. */
    public static Account register(String displayName, PasswordHash password, String address, Instant when) {
        return new Account(
                key(displayName), displayName, password, false, when, address, when, address);
    }

    /** Whether a password can be checked against this account at all. */
    public boolean hasPassword() {
        return this.password != null;
    }

    public Account withPassword(PasswordHash newPassword) {
        return new Account(
                this.name, this.displayName, newPassword, this.premium,
                this.registeredAt, this.registeredIp, this.lastLoginAt, this.lastLoginIp);
    }

    public Account withPremium(boolean nowPremium) {
        return new Account(
                this.name, this.displayName, this.password, nowPremium,
                this.registeredAt, this.registeredIp, this.lastLoginAt, this.lastLoginIp);
    }

    public Account withLogin(String address, Instant when) {
        return new Account(
                this.name, this.displayName, this.password, this.premium,
                this.registeredAt, this.registeredIp, when, address);
    }
}
