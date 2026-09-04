package pl.landmc.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import pl.landmc.auth.config.AuthConfig;
import pl.landmc.auth.password.PasswordHash;
import pl.landmc.auth.password.PasswordHasher;
import pl.landmc.auth.session.SessionStore;
import pl.landmc.auth.storage.AccountRepository;

/**
 * Who is logged in, who is still waiting, and what happens when they type a password.
 *
 * <p>Knows nothing about Velocity. Everything here is names, addresses and UUIDs, which is what
 * makes it testable without a proxy - and the login rules are the part of this plugin most
 * worth testing, because getting them subtly wrong is not visible until somebody is in an
 * account that is not theirs.
 *
 * <p>The question asked most often by far is "is this player logged in?" - once for every
 * command and every chat message from every connection. That is one lookup in a
 * {@link ConcurrentHashMap} and no I/O at all. The database is touched exactly three times in a
 * player's life here: once to read the account when they connect, once to record the login, and
 * once more only if they change something.
 */
public final class AuthService {

    private final AccountRepository accounts;
    private final SessionStore sessions;
    private final PasswordHasher hasher;
    private final AuthConfig config;
    private final Executor hashingExecutor;
    private final Logger logger;

    /** Connections that have not authenticated yet. Small: it holds only players who wait. */
    private final Map<UUID, PendingLogin> pending = new ConcurrentHashMap<>();

    /** Everyone who has authenticated on this proxy. Read on every command. */
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();

    /**
     * Accounts read during a pre-login, waiting for the post-login that follows.
     *
     * <p>Keyed by name because the pre-login has no UUID to key by yet - and because the UUID
     * it eventually gets depends on whether the player turned out to be premium, which is the
     * very thing this lookup decides.
     */
    private final Map<String, PreloadedAccount> preloaded = new ConcurrentHashMap<>();

    public AuthService(
            AccountRepository accounts,
            SessionStore sessions,
            PasswordHasher hasher,
            AuthConfig config,
            Executor hashingExecutor,
            Logger logger) {

        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.config = Objects.requireNonNull(config, "config");
        this.hashingExecutor = Objects.requireNonNull(hashingExecutor, "hashingExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // --- state ---------------------------------------------------------------------------

    /** The hot path: called for every command and every chat message. One map lookup. */
    public boolean isAuthenticated(UUID playerId) {
        return this.authenticated.contains(playerId);
    }

    public @Nullable PendingLogin pending(UUID playerId) {
        return this.pending.get(playerId);
    }

    public SessionStore sessions() {
        return this.sessions;
    }

    /**
     * Reads the account for a name that is connecting, and keeps it for the post-login.
     *
     * <p>Runs during the pre-login, which is where the proxy has to decide whether to ask
     * Mojang about this player. Doing it once here and reusing the answer is what keeps the
     * rest of the connection free of queries.
     */
    public CompletableFuture<Optional<Account>> preload(String name) {
        return this.accounts.find(name).whenComplete((account, throwable) -> {
            if (throwable == null) {
                this.preloaded.put(
                        Account.key(name),
                        new PreloadedAccount(account.orElse(null), System.currentTimeMillis()));
            }
        });
    }

    /**
     * Starts the login window for a player who has just connected.
     *
     * <p>Normally the account is already in hand from the pre-login and this completes without
     * touching the database. When it is not - a pre-login result that was swept, or a
     * connection that reached the post-login by some other route - the account is read rather
     * than assumed absent. Assuming would tell a registered player to register, and telling
     * somebody their account does not exist is how they end up believing it was deleted.
     *
     * @param onlineMode whether Mojang authenticated this connection
     * @return what the player still has to do
     */
    public CompletableFuture<Outcome> begin(
            UUID playerId, String name, String address, boolean onlineMode) {

        PreloadedAccount preload = this.preloaded.remove(Account.key(name));
        if (preload != null) {
            return CompletableFuture.completedFuture(
                    this.begin(playerId, name, address, onlineMode, preload.account()));
        }

        this.logger.debug("No pre-login result for {}; reading the account again.", name);
        return this.accounts.find(name)
                .thenApply(account ->
                        this.begin(playerId, name, address, onlineMode, account.orElse(null)));
    }

    private Outcome begin(
            UUID playerId,
            String name,
            String address,
            boolean onlineMode,
            @Nullable Account account) {

        // Mojang vouched for this connection, and the account asked us to accept that. There is
        // nothing left for the player to prove.
        if (onlineMode && account != null && account.premium()) {
            this.markAuthenticated(playerId, account, address);
            return Outcome.PREMIUM;
        }

        if (account != null
                && this.config.session.enabled
                && this.sessions.isValid(name, address)) {
            this.markAuthenticated(playerId, account, address);
            return Outcome.SESSION;
        }

        long deadline = System.currentTimeMillis()
                + Duration.ofSeconds(Math.max(1, this.config.timeoutSeconds)).toMillis();

        this.pending.put(
                playerId,
                new PendingLogin(
                        playerId,
                        name,
                        account,
                        address,
                        deadline,
                        Math.max(1, this.config.maxAttempts)));

        return account == null ? Outcome.MUST_REGISTER : Outcome.MUST_LOG_IN;
    }

    /** Forgets everything about a connection, and records a session if it was logged in. */
    public void onDisconnect(UUID playerId, String name, String address) {
        this.pending.remove(playerId);

        if (this.authenticated.remove(playerId) && this.config.session.enabled) {
            this.sessions.remember(
                    name, address, Duration.ofMinutes(Math.max(0, this.config.session.minutes)));
        }
    }

    /**
     * The connections whose time has run out.
     *
     * <p>Returned rather than acted on, because kicking a player is the caller's business and
     * this class does not know what a player is. The map only ever holds waiting connections,
     * so this walks a handful of entries, not the whole server.
     */
    public List<PendingLogin> expired(long now) {
        if (this.pending.isEmpty()) {
            return List.of();
        }

        List<PendingLogin> expired = new ArrayList<>();
        for (PendingLogin login : this.pending.values()) {
            if (login.hasExpired(now)) {
                expired.add(login);
            }
        }
        return expired;
    }

    /** Everyone still waiting, for the reminder that nags them. */
    public Iterable<PendingLogin> waiting() {
        return this.pending.values();
    }

    /**
     * Drops pre-login results that no post-login ever claimed.
     *
     * <p>A connection can die between the two - a client that gives up during the handshake,
     * or one the anti-proxy drops - and without this the map would keep one entry per such
     * attempt for as long as the proxy runs.
     */
    public void sweepPreloaded(long now, Duration maximumAge) {
        this.preloaded.values().removeIf(entry -> now - entry.loadedAt() > maximumAge.toMillis());
    }

    // --- actions -------------------------------------------------------------------------

    /** Checks a password for a waiting player. */
    public CompletableFuture<LoginResult> login(PendingLogin login, String password) {
        Account account = login.account();
        if (account == null) {
            return CompletableFuture.completedFuture(LoginResult.NOT_REGISTERED);
        }

        PasswordHash stored = account.password();
        if (stored == null) {
            // Premium-only account reached by a connection Mojang did not vouch for. There is
            // no password that can be right, and saying so would confirm the name is premium.
            return CompletableFuture.completedFuture(LoginResult.WRONG_PASSWORD);
        }

        if (!login.beginCheck()) {
            return CompletableFuture.completedFuture(LoginResult.BUSY);
        }

        char[] characters = password.toCharArray();

        return CompletableFuture
                .supplyAsync(() -> this.hasher.verify(characters, stored), this.hashingExecutor)
                .handle((matched, throwable) -> {
                    login.endCheck();

                    if (throwable != null) {
                        this.logger.error("Could not verify a password for {}", login.name(), throwable);
                        return LoginResult.FAILED;
                    }

                    if (!matched) {
                        return login.recordFailure() > 0
                                ? LoginResult.WRONG_PASSWORD
                                : LoginResult.OUT_OF_ATTEMPTS;
                    }

                    this.markAuthenticated(login.playerId(), account, login.address());
                    this.rehashIfOutdated(account, characters, stored);
                    return LoginResult.SUCCESS;
                });
    }

    /** Creates an account for a waiting player who does not have one. */
    public CompletableFuture<RegisterResult> register(
            PendingLogin login, String password, String confirmation) {

        if (login.isRegistered()) {
            return CompletableFuture.completedFuture(RegisterResult.ALREADY_REGISTERED);
        }

        if (!password.equals(confirmation)) {
            return CompletableFuture.completedFuture(RegisterResult.MISMATCH);
        }

        PasswordProblem problem = this.validate(password, login.name());
        if (problem != null) {
            return CompletableFuture.completedFuture(problem.asRegisterResult());
        }

        char[] characters = password.toCharArray();

        return CompletableFuture
                .supplyAsync(() -> this.hasher.hash(characters), this.hashingExecutor)
                .thenCompose(hash -> {
                    Account account = Account.register(
                            login.name(), hash, login.address(), Instant.now());

                    return this.accounts.create(account).thenApply(created -> {
                        if (!created) {
                            return RegisterResult.ALREADY_REGISTERED;
                        }
                        this.markAuthenticated(login.playerId(), account, login.address());
                        return RegisterResult.SUCCESS;
                    });
                })
                .exceptionally(throwable -> {
                    this.logger.error("Could not register {}", login.name(), throwable);
                    return RegisterResult.FAILED;
                });
    }

    /**
     * Changes a logged-in player's password.
     *
     * <p>The current password is required. The previous server did not ask for it - it charged
     * fifty diamonds instead - which meant anybody who reached a logged-in client could take
     * the account permanently.
     */
    public CompletableFuture<ChangeResult> changePassword(
            Account account, String currentPassword, String newPassword, String confirmation) {

        if (!newPassword.equals(confirmation)) {
            return CompletableFuture.completedFuture(ChangeResult.MISMATCH);
        }

        PasswordProblem problem = this.validate(newPassword, account.name());
        if (problem != null) {
            return CompletableFuture.completedFuture(problem.asChangeResult());
        }

        PasswordHash stored = account.password();
        char[] current = currentPassword.toCharArray();
        char[] replacement = newPassword.toCharArray();

        return CompletableFuture
                .supplyAsync(
                        () -> {
                            // A premium-only account has no current password to prove, and the
                            // player is already authenticated by Mojang to be standing here.
                            if (stored != null && !this.hasher.verify(current, stored)) {
                                return null;
                            }
                            return this.hasher.hash(replacement);
                        },
                        this.hashingExecutor)
                .thenCompose(hash -> {
                    if (hash == null) {
                        return CompletableFuture.completedFuture(ChangeResult.WRONG_PASSWORD);
                    }

                    return this.accounts.updatePassword(account.name(), hash).thenApply(ignored -> {
                        // Any session was created under the old password; a password change is
                        // usually somebody taking their account back.
                        this.sessions.forget(account.name());
                        return ChangeResult.SUCCESS;
                    });
                })
                .exceptionally(throwable -> {
                    this.logger.error("Could not change the password for {}", account.name(), throwable);
                    return ChangeResult.FAILED;
                });
    }

    /** Turns Mojang authentication on or off for an account. */
    public CompletableFuture<Void> setPremium(String name, boolean premium) {
        return this.accounts.updatePremium(name, premium)
                .thenRun(() -> this.sessions.forget(name));
    }

    /** The account a logged-in player is using, re-read from the database. */
    public CompletableFuture<Optional<Account>> account(String name) {
        return this.accounts.find(name);
    }

    // --- internals -----------------------------------------------------------------------

    private void markAuthenticated(UUID playerId, Account account, String address) {
        this.pending.remove(playerId);
        this.authenticated.add(playerId);

        this.accounts.recordLogin(account.name(), address, Instant.now())
                .exceptionally(throwable -> {
                    // The player is in. Failing to write the timestamp is worth a line in the
                    // log and nothing more; it must not turn into a failed login.
                    this.logger.warn("Could not record the login of {}", account.name(), throwable);
                    return null;
                });
    }

    /**
     * Replaces a hash that was produced by an older scheme.
     *
     * <p>This is the only moment the plaintext of an existing password is known, so it is the
     * only moment an imported SHA-256 account, or one hashed with fewer rounds, can be
     * upgraded. Runs after the player is already logged in: if it fails, they are still in and
     * the old hash still works.
     */
    private void rehashIfOutdated(Account account, char[] password, PasswordHash stored) {
        if (!stored.isOutdated(this.hasher.iterations())) {
            return;
        }

        CompletableFuture
                .supplyAsync(() -> this.hasher.hash(password), this.hashingExecutor)
                .thenCompose(hash -> this.accounts.updatePassword(account.name(), hash))
                .thenRun(() -> this.logger.info("Rehashed the password of {}.", account.name()))
                .exceptionally(throwable -> {
                    this.logger.warn("Could not rehash the password of {}", account.name(), throwable);
                    return null;
                });
    }

    private @Nullable PasswordProblem validate(String password, String name) {
        AuthConfig.PasswordSection rules = this.config.password;

        if (password.length() < rules.minimumLength) {
            return PasswordProblem.TOO_SHORT;
        }
        if (password.length() > rules.maximumLength) {
            return PasswordProblem.TOO_LONG;
        }

        String lowered = password.toLowerCase(Locale.ROOT);
        if (lowered.equals(Account.key(name)) || rules.forbiddenPasswords.contains(lowered)) {
            return PasswordProblem.TOO_COMMON;
        }

        return null;
    }

    // --- results -------------------------------------------------------------------------

    /** What a freshly connected player still has to do. */
    public enum Outcome {
        /** Mojang authenticated them and the account allows it. */
        PREMIUM,
        /** They came back from the same address inside the session window. */
        SESSION,
        MUST_LOG_IN,
        MUST_REGISTER
    }

    public enum LoginResult {
        SUCCESS,
        WRONG_PASSWORD,
        /** The last attempt is gone; the caller disconnects them. */
        OUT_OF_ATTEMPTS,
        NOT_REGISTERED,
        /** Another attempt from this connection is still being checked. */
        BUSY,
        FAILED
    }

    public enum RegisterResult {
        SUCCESS,
        ALREADY_REGISTERED,
        MISMATCH,
        TOO_SHORT,
        TOO_LONG,
        TOO_COMMON,
        FAILED
    }

    public enum ChangeResult {
        SUCCESS,
        WRONG_PASSWORD,
        MISMATCH,
        TOO_SHORT,
        TOO_LONG,
        TOO_COMMON,
        FAILED
    }

    private enum PasswordProblem {
        TOO_SHORT,
        TOO_LONG,
        TOO_COMMON;

        RegisterResult asRegisterResult() {
            return switch (this) {
                case TOO_SHORT -> RegisterResult.TOO_SHORT;
                case TOO_LONG -> RegisterResult.TOO_LONG;
                case TOO_COMMON -> RegisterResult.TOO_COMMON;
            };
        }

        ChangeResult asChangeResult() {
            return switch (this) {
                case TOO_SHORT -> ChangeResult.TOO_SHORT;
                case TOO_LONG -> ChangeResult.TOO_LONG;
                case TOO_COMMON -> ChangeResult.TOO_COMMON;
            };
        }
    }

    private record PreloadedAccount(@Nullable Account account, long loadedAt) {
    }
}
