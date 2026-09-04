package pl.landmc.auth.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import pl.landmc.auth.password.PasswordHash.Algorithm;

/**
 * Turns a typed password into something safe to store, and checks one against what is stored.
 *
 * <p>PBKDF2-HMAC-SHA512, from the JDK. Deliberately not a library: adding a dependency to a
 * shaded plugin jar for something {@link SecretKeyFactory} already implements buys nothing, and
 * a password database is the last place to depend on a third party staying maintained.
 *
 * <p>The point of a KDF is that it is slow. A single SHA-256 - which is what the previous
 * server stored - is a few microseconds, so an attacker holding the table tries the whole of a
 * leaked password list in an afternoon. At the iteration count below one guess costs roughly a
 * tenth of a second, which is unnoticeable once per login and ruinous a billion times over.
 *
 * <p>That cost is real for us too, which is why every call here belongs on an executor and
 * never on a Netty thread: a login burst would otherwise stall the whole proxy's I/O.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA512";

    /** OWASP's recommendation for PBKDF2-HMAC-SHA512 at the time of writing. */
    public static final int DEFAULT_ITERATIONS = 210_000;

    /** 128 bits. Longer buys nothing; the salt only has to be unique, not unguessable. */
    private static final int SALT_LENGTH = 16;

    /** The full SHA-512 output. Truncating it would only make collisions cheaper. */
    private static final int KEY_LENGTH_BITS = 512;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    public PasswordHasher(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        this.iterations = iterations;
    }

    public int iterations() {
        return this.iterations;
    }

    /** Hashes a password with a fresh salt. Blocking, and deliberately slow. */
    public PasswordHash hash(char[] password) {
        byte[] salt = new byte[SALT_LENGTH];
        this.random.nextBytes(salt);

        return new PasswordHash(
                Algorithm.PBKDF2_SHA512, this.iterations, salt, derive(password, salt, this.iterations));
    }

    /**
     * Whether a typed password produces the stored hash.
     *
     * <p>Runs at the cost the stored value asks for, not the current one - otherwise an
     * account hashed under an older iteration count could never verify again.
     */
    public boolean verify(char[] password, PasswordHash stored) {
        return switch (stored.algorithm()) {
            case PBKDF2_SHA512 ->
                    stored.matches(derive(password, stored.salt(), stored.iterations()));
            case SHA256_LEGACY -> stored.matches(sha256(password));
        };
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        }
        catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA512 is not available", exception);
        }
        finally {
            // The spec keeps its own copy of the characters until this runs.
            spec.clearPassword();
        }
    }

    /**
     * The old server's hash, for verifying imported accounts only.
     *
     * <p>It hashed the string as UTF-8 with no salt and one round. Reproducing it exactly is
     * the only way an imported account can log in once and be rewritten properly.
     */
    private static byte[] sha256(char[] password) {
        byte[] bytes = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
