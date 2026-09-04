package pl.landmc.auth.storage;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

/**
 * How an account is stored. Nothing outside {@link AccountRepository} touches this.
 *
 * <p>The primary key is the lower-cased name, not a UUID, and that is the single most
 * consequential decision in this plugin. A player who turns premium on stops being identified
 * by an offline UUID derived from their name and starts being identified by Mojang's, so a
 * UUID-keyed account table would lose the account at the exact moment it mattered. The name is
 * what stays the same across that switch, and Minecraft already guarantees it is unique.
 *
 * <p>The rest of the network keys on UUID, which is only safe because
 * {@code GameProfileListener} rewrites every profile back to the offline UUID before it reaches
 * a backend. See that class for why.
 */
@DatabaseTable(tableName = "auth_accounts")
public class AccountEntity {

    @DatabaseField(id = true, columnName = "name", width = 16)
    public String name;

    /** The capitalisation the player actually registered with, for greeting them by it. */
    @DatabaseField(canBeNull = false, columnName = "display_name", width = 16)
    public String displayName;

    /**
     * Null for an account that only ever logs in through Mojang.
     *
     * <p>Storing an empty string instead would make "has no password" and "has a password that
     * happens to be empty" the same value, and one of those must never verify.
     */
    @DatabaseField(columnName = "password_hash", width = 256)
    public String passwordHash;

    /** Whether Mojang authenticates this player instead of a password. */
    @DatabaseField(columnName = "premium")
    public boolean premium;

    @DatabaseField(columnName = "registered_at")
    public long registeredAt;

    @DatabaseField(columnName = "registered_ip", width = 45)
    public String registeredIp;

    @DatabaseField(columnName = "last_login_at")
    public long lastLoginAt;

    @DatabaseField(columnName = "last_login_ip", width = 45)
    public String lastLoginIp;

    /** Required by ORMLite. */
    public AccountEntity() {
    }
}
