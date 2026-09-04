package pl.landmc.auth.storage;

import com.j256.ormlite.dao.Dao;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import pl.landmc.auth.Account;
import pl.landmc.auth.password.PasswordHash;
import pl.landmc.platform.database.DatabaseService;

/**
 * Reads and writes accounts. Every method here goes to the database, so every method here is
 * asynchronous - there is no overload that quietly blocks, because the callers are login events
 * on Netty threads and one blocking call among them is the whole proxy stalling.
 *
 * <p>Nothing caches here. The cache belongs to {@link pl.landmc.auth.AuthService}, which knows
 * when a player arrives and when they leave; a repository that cached would have no idea when
 * to forget.
 */
public final class AccountRepository {

    private final DatabaseService database;

    public AccountRepository(DatabaseService database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void createTables() {
        this.database.createTables(AccountEntity.class);
    }

    /** Looks an account up by name, in any capitalisation. */
    public CompletableFuture<Optional<Account>> find(String name) {
        String key = Account.key(name);
        return this.database.supplyAsync(() -> Optional.ofNullable(this.dao().queryForId(key))
                .map(AccountRepository::toAccount));
    }

    /**
     * Stores a new account, and reports whether this call is the one that created it.
     *
     * <p>False means somebody registered the same name first. That is not a race worth locking
     * against - two people cannot hold the same name at once - but a player who reconnects
     * mid-registration can produce it, and it must not surface as a stack trace.
     */
    public CompletableFuture<Boolean> create(Account account) {
        return this.database.supplyAsync(() -> {
            Dao<AccountEntity, String> dao = this.dao();
            if (dao.idExists(account.name())) {
                return false;
            }
            dao.create(toEntity(account));
            return true;
        });
    }

    /** Replaces the stored password. Used by a password change and by a silent rehash. */
    public CompletableFuture<Void> updatePassword(String name, PasswordHash password) {
        String key = Account.key(name);
        String serialized = password.serialize();

        return this.database.runAsync(() -> {
            var builder = this.dao().updateBuilder();
            builder.updateColumnValue("password_hash", serialized);
            builder.where().idEq(key);
            builder.update();
        });
    }

    public CompletableFuture<Void> updatePremium(String name, boolean premium) {
        String key = Account.key(name);

        return this.database.runAsync(() -> {
            var builder = this.dao().updateBuilder();
            builder.updateColumnValue("premium", premium);
            builder.where().idEq(key);
            builder.update();
        });
    }

    /**
     * Records a successful login.
     *
     * <p>Two columns, written once per login - not a row per login. A login history is a
     * different table with a different retention policy, and conflating them means the accounts
     * table grows without bound.
     */
    public CompletableFuture<Void> recordLogin(String name, String address, Instant when) {
        String key = Account.key(name);

        return this.database.runAsync(() -> {
            var builder = this.dao().updateBuilder();
            builder.updateColumnValue("last_login_at", when.toEpochMilli());
            builder.updateColumnValue("last_login_ip", address);
            builder.where().idEq(key);
            builder.update();
        });
    }

    private Dao<AccountEntity, String> dao() {
        return this.database.dao(AccountEntity.class);
    }

    private static Account toAccount(AccountEntity entity) {
        return new Account(
                entity.name,
                entity.displayName,
                entity.passwordHash == null || entity.passwordHash.isEmpty()
                        ? null
                        : PasswordHash.parse(entity.passwordHash),
                entity.premium,
                Instant.ofEpochMilli(entity.registeredAt),
                entity.registeredIp,
                entity.lastLoginAt == 0L ? null : Instant.ofEpochMilli(entity.lastLoginAt),
                entity.lastLoginIp);
    }

    private static AccountEntity toEntity(Account account) {
        AccountEntity entity = new AccountEntity();
        entity.name = account.name();
        entity.displayName = account.displayName();
        entity.passwordHash = account.password() == null ? null : account.password().serialize();
        entity.premium = account.premium();
        entity.registeredAt = account.registeredAt().toEpochMilli();
        entity.registeredIp = account.registeredIp();
        entity.lastLoginAt = account.lastLoginAt() == null ? 0L : account.lastLoginAt().toEpochMilli();
        entity.lastLoginIp = account.lastLoginIp();
        return entity;
    }
}
