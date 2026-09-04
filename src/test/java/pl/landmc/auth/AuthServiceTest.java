package pl.landmc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import pl.landmc.auth.AuthService.ChangeResult;
import pl.landmc.auth.AuthService.LoginResult;
import pl.landmc.auth.AuthService.Outcome;
import pl.landmc.auth.AuthService.RegisterResult;
import pl.landmc.auth.config.AuthConfig;
import pl.landmc.auth.password.PasswordHasher;
import pl.landmc.auth.session.SessionStore;
import pl.landmc.auth.storage.AccountRepository;
import pl.landmc.platform.database.DatabaseService;
import pl.landmc.platform.database.DatabaseType;

/**
 * The login rules, against a real embedded database.
 *
 * <p>These are the tests worth having in this project. Every one of them is a way somebody
 * could end up inside an account that is not theirs, or locked out of one that is - and none of
 * that is visible by reading the code, because the interesting cases are the combinations: a
 * premium flag with no password, a session from a different address, a second attempt arriving
 * while the first is still being hashed.
 */
class AuthServiceTest {

    private static final String ADDRESS = "203.0.113.7";
    private static final String OTHER_ADDRESS = "203.0.113.8";

    private DatabaseService database;
    private AccountRepository accounts;
    private SessionStore sessions;
    private AuthConfig config;
    private AuthService auth;

    @BeforeEach
    void openDatabase(@TempDir Path directory) {
        this.config = new AuthConfig();
        this.config.database.type = DatabaseType.H2;
        this.config.database.fileName = "auth-test";
        this.config.database.poolSize = 4;
        this.config.maxAttempts = 3;
        this.config.password.minimumLength = 6;
        // Fast enough that a test suite is not a minute of arithmetic.
        this.config.password.iterations = 1_000;

        this.database = new DatabaseService(
                "auth-test", this.config.database, directory, NOPLogger.NOP_LOGGER);
        this.database.enable();

        this.accounts = new AccountRepository(this.database);
        this.accounts.createTables();
        this.sessions = new SessionStore();

        this.auth = new AuthService(
                this.accounts,
                this.sessions,
                new PasswordHasher(this.config.password.iterations),
                this.config,
                Runnable::run,
                NOPLogger.NOP_LOGGER);
    }

    @AfterEach
    void closeDatabase() {
        if (this.database != null) {
            this.database.close();
        }
    }

    // --- registering ---------------------------------------------------------------------

    @Test
    @DisplayName("a new player is asked to register, and is logged in once they have")
    void registersANewPlayer() throws Exception {
        UUID id = UUID.randomUUID();
        PendingLogin pending = this.begin(id, "Crispi");

        assertFalse(pending.isRegistered());
        assertFalse(this.auth.isAuthenticated(id));

        assertEquals(
                RegisterResult.SUCCESS,
                await(this.auth.register(pending, "tajneHaslo", "tajneHaslo")));
        assertTrue(this.auth.isAuthenticated(id));
        assertNull(this.auth.pending(id), "the player is still listed as waiting after registering");
    }

    @Test
    @DisplayName("registration refuses two different passwords and anything too obvious")
    void refusesABadPassword() throws Exception {
        PendingLogin pending = this.begin(UUID.randomUUID(), "Crispi");

        assertEquals(RegisterResult.MISMATCH, await(this.auth.register(pending, "haslo1", "haslo2")));
        assertEquals(RegisterResult.TOO_SHORT, await(this.auth.register(pending, "abc", "abc")));
        assertEquals(
                RegisterResult.TOO_LONG,
                await(this.auth.register(pending, "a".repeat(200), "a".repeat(200))));
        assertEquals(
                RegisterResult.TOO_COMMON,
                await(this.auth.register(pending, "password", "password")));

        // The name itself is the first thing anybody tries.
        assertEquals(RegisterResult.TOO_COMMON, await(this.auth.register(pending, "crispi", "crispi")));
        assertEquals(RegisterResult.TOO_COMMON, await(this.auth.register(pending, "CrIsPi", "CrIsPi")));
    }

    @Test
    @DisplayName("a name that is already taken cannot be registered again, in any capitalisation")
    void refusesADuplicateName() throws Exception {
        await(this.auth.register(this.begin(UUID.randomUUID(), "Crispi"), "tajneHaslo", "tajneHaslo"));

        // A second player connecting as CRISPI must not be able to overwrite the account.
        PendingLogin second = this.begin(UUID.randomUUID(), "CRISPI");
        assertTrue(second.isRegistered(), "the account was not found under a different capitalisation");
        assertEquals(
                RegisterResult.ALREADY_REGISTERED,
                await(this.auth.register(second, "inneHaslo", "inneHaslo")));
    }

    // --- logging in ----------------------------------------------------------------------

    @Test
    @DisplayName("the right password logs in, the wrong one does not")
    void checksThePassword() throws Exception {
        this.register("Crispi", "tajneHaslo");

        UUID id = UUID.randomUUID();
        PendingLogin pending = this.begin(id, "Crispi");
        assertTrue(pending.isRegistered());

        assertEquals(LoginResult.WRONG_PASSWORD, await(this.auth.login(pending, "zleHaslo")));
        assertFalse(this.auth.isAuthenticated(id));

        assertEquals(LoginResult.SUCCESS, await(this.auth.login(pending, "tajneHaslo")));
        assertTrue(this.auth.isAuthenticated(id));
    }

    @Test
    @DisplayName("the attempts run out and say so on the last one")
    void countsAttempts() throws Exception {
        this.register("Crispi", "tajneHaslo");
        PendingLogin pending = this.begin(UUID.randomUUID(), "Crispi");

        assertEquals(LoginResult.WRONG_PASSWORD, await(this.auth.login(pending, "raz")));
        assertEquals(2, pending.attemptsLeft());
        assertEquals(LoginResult.WRONG_PASSWORD, await(this.auth.login(pending, "dwa")));
        assertEquals(1, pending.attemptsLeft());
        assertEquals(LoginResult.OUT_OF_ATTEMPTS, await(this.auth.login(pending, "trzy")));
        assertEquals(0, pending.attemptsLeft());
    }

    @Test
    @DisplayName("a second attempt sent while the first is still hashing is refused, not counted")
    void refusesConcurrentAttempts() throws Exception {
        this.register("Crispi", "tajneHaslo");
        PendingLogin pending = this.begin(UUID.randomUUID(), "Crispi");

        // Holding the check open is what a real slow hash does; without the guard, a client
        // firing ten attempts at once would have all ten checked against a limit of three.
        assertTrue(pending.beginCheck());
        assertEquals(LoginResult.BUSY, await(this.auth.login(pending, "cokolwiek")));
        assertEquals(3, pending.attemptsLeft(), "a refused attempt was counted against the limit");

        pending.endCheck();
        assertEquals(LoginResult.SUCCESS, await(this.auth.login(pending, "tajneHaslo")));
    }

    @Test
    @DisplayName("the name is matched however it is capitalised")
    void ignoresCapitalisation() throws Exception {
        this.register("Crispi", "tajneHaslo");

        PendingLogin pending = this.begin(UUID.randomUUID(), "cRiSpI");
        assertEquals(LoginResult.SUCCESS, await(this.auth.login(pending, "tajneHaslo")));
    }

    // --- sessions ------------------------------------------------------------------------

    @Test
    @DisplayName("coming back from the same address skips the password, from another does not")
    void honoursSessions() throws Exception {
        this.register("Crispi", "tajneHaslo");

        UUID first = UUID.randomUUID();
        await(this.auth.begin(first, "Crispi", ADDRESS, false));
        await(this.auth.login(this.auth.pending(first), "tajneHaslo"));
        this.auth.onDisconnect(first, "Crispi", ADDRESS);

        this.preload("Crispi");
        assertEquals(
                Outcome.SESSION,
                await(this.auth.begin(UUID.randomUUID(), "Crispi", ADDRESS, false)));

        this.preload("Crispi");
        assertEquals(
                Outcome.MUST_LOG_IN,
                await(this.auth.begin(UUID.randomUUID(), "Crispi", OTHER_ADDRESS, false)),
                "a session was accepted from an address that did not create it");
    }

    @Test
    @DisplayName("a session is not created for a player who never logged in")
    void doesNotRememberAFailedLogin() throws Exception {
        this.register("Crispi", "tajneHaslo");

        UUID id = UUID.randomUUID();
        this.preload("Crispi");
        await(this.auth.begin(id, "Crispi", ADDRESS, false));
        await(this.auth.login(this.auth.pending(id), "zleHaslo"));
        this.auth.onDisconnect(id, "Crispi", ADDRESS);

        assertEquals(0, this.sessions.size());
    }

    @Test
    @DisplayName("changing the password drops the session it was created under")
    void dropsTheSessionOnAPasswordChange() throws Exception {
        this.register("Crispi", "tajneHaslo");

        UUID id = UUID.randomUUID();
        this.preload("Crispi");
        await(this.auth.begin(id, "Crispi", ADDRESS, false));
        await(this.auth.login(this.auth.pending(id), "tajneHaslo"));
        this.auth.onDisconnect(id, "Crispi", ADDRESS);
        assertTrue(this.sessions.isValid("Crispi", ADDRESS));

        Account account = await(this.auth.account("Crispi")).orElseThrow();
        assertEquals(
                ChangeResult.SUCCESS,
                await(this.auth.changePassword(account, "tajneHaslo", "noweHaslo", "noweHaslo")));

        // Somebody taking their account back must not leave the intruder's session working.
        assertFalse(this.sessions.isValid("Crispi", ADDRESS));
    }

    // --- premium -------------------------------------------------------------------------

    @Test
    @DisplayName("a premium account authenticated by Mojang skips the password entirely")
    void letsAPremiumAccountStraightIn() throws Exception {
        this.register("Crispi", "tajneHaslo");
        await(this.auth.setPremium("Crispi", true));

        this.preload("Crispi");
        UUID id = UUID.randomUUID();
        assertEquals(Outcome.PREMIUM, await(this.auth.begin(id, "Crispi", ADDRESS, true)));
        assertTrue(this.auth.isAuthenticated(id));
    }

    @Test
    @DisplayName("a premium account reached without Mojang still has to type its password")
    void stillAsksWhenMojangDidNotVouch() throws Exception {
        this.register("Crispi", "tajneHaslo");
        await(this.auth.setPremium("Crispi", true));

        this.preload("Crispi");
        UUID id = UUID.randomUUID();

        // onlineMode false: the connection was not authenticated. Trusting the flag alone here
        // would mean anybody could take a premium account by connecting as its name.
        assertEquals(Outcome.MUST_LOG_IN, await(this.auth.begin(id, "Crispi", ADDRESS, false)));
        assertFalse(this.auth.isAuthenticated(id));
        assertEquals(LoginResult.SUCCESS, await(this.auth.login(this.auth.pending(id), "tajneHaslo")));
    }

    @Test
    @DisplayName("a non-premium account is not let in just because Mojang authenticated it")
    void doesNotTrustOnlineModeAlone() throws Exception {
        this.register("Crispi", "tajneHaslo");

        this.preload("Crispi");
        UUID id = UUID.randomUUID();
        assertEquals(Outcome.MUST_LOG_IN, await(this.auth.begin(id, "Crispi", ADDRESS, true)));
        assertFalse(this.auth.isAuthenticated(id));
    }

    // --- changing a password -------------------------------------------------------------

    @Test
    @DisplayName("changing a password needs the current one")
    void requiresTheCurrentPassword() throws Exception {
        this.register("Crispi", "tajneHaslo");
        Account account = await(this.auth.account("Crispi")).orElseThrow();

        assertEquals(
                ChangeResult.WRONG_PASSWORD,
                await(this.auth.changePassword(account, "nieToHaslo", "noweHaslo", "noweHaslo")));
        assertEquals(
                ChangeResult.MISMATCH,
                await(this.auth.changePassword(account, "tajneHaslo", "noweHaslo", "inneHaslo")));
        assertEquals(
                ChangeResult.TOO_SHORT,
                await(this.auth.changePassword(account, "tajneHaslo", "abc", "abc")));

        // None of the refusals may have written anything.
        assertEquals(
                LoginResult.SUCCESS,
                await(this.auth.login(this.begin(UUID.randomUUID(), "Crispi"), "tajneHaslo")));
    }

    @Test
    @DisplayName("after a change the new password works and the old one does not")
    void changesThePassword() throws Exception {
        this.register("Crispi", "tajneHaslo");
        Account account = await(this.auth.account("Crispi")).orElseThrow();

        assertEquals(
                ChangeResult.SUCCESS,
                await(this.auth.changePassword(account, "tajneHaslo", "noweHaslo", "noweHaslo")));

        assertEquals(
                LoginResult.WRONG_PASSWORD,
                await(this.auth.login(this.begin(UUID.randomUUID(), "Crispi"), "tajneHaslo")));
        assertEquals(
                LoginResult.SUCCESS,
                await(this.auth.login(this.begin(UUID.randomUUID(), "Crispi"), "noweHaslo")));
    }

    // --- imported accounts ---------------------------------------------------------------

    @Test
    @DisplayName("an account imported from the old server logs in once and is rehashed")
    void upgradesAnImportedAccount() throws Exception {
        // Exactly what the previous server left in its table.
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("stareHaslo".getBytes(StandardCharsets.UTF_8));
        String legacy = "sha256$" + HexFormat.of().formatHex(digest);

        await(this.accounts.create(new Account(
                "crispi", "Crispi", pl.landmc.auth.password.PasswordHash.parse(legacy),
                false, java.time.Instant.now(), ADDRESS, null, null)));

        PendingLogin pending = this.begin(UUID.randomUUID(), "Crispi");
        assertEquals(LoginResult.SUCCESS, await(this.auth.login(pending, "stareHaslo")));

        // The rehash is deliberately fire-and-forget - a player is already logged in by the
        // time it runs, and it must never be able to fail their login - so this waits for the
        // effect rather than for a future.
        Account stored = awaitRehash("Crispi");
        assertNotNull(stored.password());
        assertEquals(
                pl.landmc.auth.password.PasswordHash.Algorithm.PBKDF2_SHA512,
                stored.password().algorithm(),
                "an imported account was left on the old hash after logging in");
        assertEquals(
                LoginResult.SUCCESS,
                await(this.auth.login(this.begin(UUID.randomUUID(), "Crispi"), "stareHaslo")));
    }

    // --- the waiting window ---------------------------------------------------------------

    @Test
    @DisplayName("a player who never logs in shows up as expired, and one who does never does")
    void expiresWaitingPlayers() throws Exception {
        this.register("Crispi", "tajneHaslo");

        UUID waiting = UUID.randomUUID();
        this.preload("Crispi");
        await(this.auth.begin(waiting, "Crispi", ADDRESS, false));

        assertTrue(this.auth.expired(System.currentTimeMillis()).isEmpty());

        long afterTheDeadline =
                System.currentTimeMillis() + (this.config.timeoutSeconds + 1) * 1_000L;
        assertEquals(1, this.auth.expired(afterTheDeadline).size());

        await(this.auth.login(this.auth.pending(waiting), "tajneHaslo"));
        assertTrue(
                this.auth.expired(afterTheDeadline).isEmpty(),
                "a player who logged in was still going to be disconnected for not logging in");
    }

    @Test
    @DisplayName("a swept pre-login result is read again rather than treated as no account")
    void survivesASweptPreload() throws Exception {
        this.register("Crispi", "tajneHaslo");
        this.preload("Crispi");

        this.auth.sweepPreloaded(
                System.currentTimeMillis() + 60_000, java.time.Duration.ofSeconds(30));

        // Falling back to "no account" here would tell a registered player to register, and
        // then refuse the registration because the name is taken - locking them out of an
        // account that is perfectly fine.
        assertEquals(
                Outcome.MUST_LOG_IN,
                await(this.auth.begin(UUID.randomUUID(), "Crispi", ADDRESS, false)));
    }

    // --- helpers -------------------------------------------------------------------------

    private void register(String name, String password) throws Exception {
        PendingLogin pending = this.begin(UUID.randomUUID(), name);
        assertEquals(RegisterResult.SUCCESS, await(this.auth.register(pending, password, password)));
    }

    /** Goes through the pre-login and the post-login the way a real connection does. */
    private PendingLogin begin(UUID id, String name) throws Exception {
        this.preload(name);
        await(this.auth.begin(id, name, ADDRESS, false));

        PendingLogin pending = this.auth.pending(id);
        assertNotNull(pending, "the player was let in without authenticating");
        return pending;
    }

    private void preload(String name) throws Exception {
        Optional<Account> ignored = await(this.auth.preload(name));
    }

    /** Waits for the background rehash to land, and fails with a clear reason if it never does. */
    private Account awaitRehash(String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (System.nanoTime() < deadline) {
            Account account = await(this.auth.account(name)).orElseThrow();
            if (account.password() != null && !account.password().isOutdated(
                    this.config.password.iterations)) {
                return account;
            }
            Thread.sleep(20);
        }

        throw new AssertionError("the password of " + name + " was never rehashed");
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }
}
