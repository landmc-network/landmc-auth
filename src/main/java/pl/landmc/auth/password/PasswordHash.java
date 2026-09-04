package pl.landmc.auth.password;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * A stored password, and the algorithm it was stored with.
 *
 * <p>The algorithm is written into the value rather than assumed, because a password database
 * outlives the decision that produced it. When the iteration count is raised - and it will be,
 * hardware gets faster - accounts hashed under the old count still verify, and are quietly
 * rewritten the next time their owner logs in. The alternative is a migration that either
 * locks everybody out or never happens.
 *
 * <p>Two formats are understood:
 *
 * <ul>
 *   <li>{@code pbkdf2-sha512$<iterations>$<salt>$<hash>} - what this network writes.
 *   <li>{@code sha256$<hex>} - unsalted SHA-256, which is what the old server stored. It is
 *       accepted for verification only, so imported accounts can log in once and be upgraded.
 *       Nothing produces it.
 * </ul>
 */
public record PasswordHash(Algorithm algorithm, int iterations, byte[] salt, byte[] hash) {

    /** Separator between the fields of a stored value. Not valid Base64, so it cannot collide. */
    private static final char FIELD = '$';

    public enum Algorithm {
        PBKDF2_SHA512("pbkdf2-sha512"),

        /**
         * Unsalted SHA-256.
         *
         * <p>Unsalted means one rainbow table breaks every account at once, and a single round
         * means a consumer GPU tries billions of candidates a second. It exists here to read
         * what the previous server left behind, never to write.
         */
        SHA256_LEGACY("sha256");

        private final String identifier;

        Algorithm(String identifier) {
            this.identifier = identifier;
        }

        public String identifier() {
            return this.identifier;
        }

        static Algorithm byIdentifier(String identifier) {
            for (Algorithm algorithm : values()) {
                if (algorithm.identifier.equals(identifier)) {
                    return algorithm;
                }
            }
            throw new IllegalArgumentException("Unknown password algorithm: " + identifier);
        }
    }

    public PasswordHash {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(hash, "hash");
    }

    /** Parses a value as it is stored in the database. */
    public static PasswordHash parse(String stored) {
        Objects.requireNonNull(stored, "stored");

        int firstSeparator = stored.indexOf(FIELD);
        if (firstSeparator < 0) {
            throw new IllegalArgumentException("Password hash has no algorithm prefix");
        }

        Algorithm algorithm = Algorithm.byIdentifier(stored.substring(0, firstSeparator));
        String rest = stored.substring(firstSeparator + 1);

        return switch (algorithm) {
            case SHA256_LEGACY -> new PasswordHash(algorithm, 1, new byte[0], decodeHex(rest));
            case PBKDF2_SHA512 -> parsePbkdf2(rest);
        };
    }

    private static PasswordHash parsePbkdf2(String rest) {
        String[] parts = rest.split("\\" + FIELD, -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed pbkdf2 password hash");
        }

        int iterations = Integer.parseInt(parts[0]);
        if (iterations < 1) {
            throw new IllegalArgumentException("Password hash iteration count must be positive");
        }

        Base64.Decoder decoder = Base64.getDecoder();
        return new PasswordHash(
                Algorithm.PBKDF2_SHA512, iterations, decoder.decode(parts[1]), decoder.decode(parts[2]));
    }

    /** The value to store. */
    public String serialize() {
        if (this.algorithm == Algorithm.SHA256_LEGACY) {
            // Only ever produced by round-tripping an imported value; nothing here creates one.
            return this.algorithm.identifier() + FIELD + encodeHex(this.hash);
        }

        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return this.algorithm.identifier()
                + FIELD + this.iterations
                + FIELD + encoder.encodeToString(this.salt)
                + FIELD + encoder.encodeToString(this.hash);
    }

    /**
     * Whether this hash should be replaced the next time the password is known.
     *
     * <p>True for anything imported, and for anything hashed with fewer rounds than we now
     * consider enough.
     */
    public boolean isOutdated(int currentIterations) {
        return this.algorithm != Algorithm.PBKDF2_SHA512 || this.iterations < currentIterations;
    }

    /** Constant-time comparison against a freshly computed digest. */
    public boolean matches(byte[] candidate) {
        return MessageDigest.isEqual(this.hash, candidate);
    }

    private static byte[] decodeHex(String hex) {
        int length = hex.length();
        if (length % 2 != 0) {
            throw new IllegalArgumentException("Hex password hash has an odd length");
        }

        byte[] bytes = new byte[length / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(hex.charAt(index * 2), 16);
            int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Hex password hash contains a non-hex character");
            }
            bytes[index] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static String encodeHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString().toLowerCase(Locale.ROOT);
    }
}
