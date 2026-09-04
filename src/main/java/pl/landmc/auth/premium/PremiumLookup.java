package pl.landmc.auth.premium;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Asks Mojang whether a name belongs to a bought account.
 *
 * <p>Used for one thing: refusing {@code /premium} to a player whose name is not a Mojang
 * account at all. Turning the flag on makes the proxy demand Mojang authentication from that
 * account for ever after, so a player who does not own the game locks themselves out with one
 * command and cannot type the one that would undo it. That is a support ticket per accident, and
 * this turns most of them into a message.
 *
 * <p>It is not a proof of ownership and is not treated as one - nobody is let in because of what
 * this returns. It only ever blocks a request, so a wrong answer costs a player one refused
 * command rather than somebody else's account.
 *
 * <p>Answers are cached because a name's status effectively never changes and Mojang rate-limits
 * the endpoint; a failure is deliberately not cached, so an outage does not keep refusing for
 * the rest of the day.
 */
public final class PremiumLookup {

    private static final String MOJANG = "https://api.mojang.com/users/profiles/minecraft/";

    /**
     * What may be appended to that URL.
     *
     * <p>The name comes from a connection, so it is checked rather than trusted: anything else
     * is refused before it can become part of a request.
     */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** Long enough that a busy evening asks once per name, short enough to notice a change. */
    private static final Duration CACHE_FOR = Duration.ofHours(6);

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;
    private final String endpoint;
    private final Logger logger;
    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();

    public PremiumLookup(Logger logger) {
        this(logger, MOJANG);
    }

    /**
     * @param endpoint the URL a name is appended to; the tests point it at a local server, so
     *     the suite never depends on Mojang being up or on the machine having a network
     */
    public PremiumLookup(Logger logger, String endpoint) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * Whether Mojang knows this name.
     *
     * @return true when the name is a paid account; {@link Answer#UNKNOWN} when Mojang could
     *     not be reached, which callers must treat as "do not block", not as "yes"
     */
    public CompletableFuture<Answer> lookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);

        CachedAnswer cached = this.cache.get(key);
        if (cached != null && !cached.hasExpired()) {
            return CompletableFuture.completedFuture(cached.answer());
        }

        if (!VALID_NAME.matcher(key).matches()) {
            return CompletableFuture.completedFuture(Answer.NOT_PREMIUM);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(this.endpoint + key))
                .timeout(TIMEOUT)
                .GET()
                .build();

        return this.http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        this.logger.warn("Could not ask Mojang about {}: {}", name, throwable.toString());
                        return Answer.UNKNOWN;
                    }

                    Answer answer = switch (response.statusCode()) {
                        case 200 -> Answer.PREMIUM;
                        // 204 is what the endpoint used to return for an unknown name, and 404
                        // is what it returns now. Both mean the same thing.
                        case 204, 404 -> Answer.NOT_PREMIUM;
                        default -> {
                            this.logger.warn(
                                    "Mojang answered {} for {}; treating it as unknown.",
                                    response.statusCode(), name);
                            yield Answer.UNKNOWN;
                        }
                    };

                    if (answer != Answer.UNKNOWN) {
                        this.cache.put(key, new CachedAnswer(
                                answer, System.currentTimeMillis() + CACHE_FOR.toMillis()));
                    }
                    return answer;
                });
    }

    public enum Answer {
        PREMIUM,
        NOT_PREMIUM,
        /** Mojang could not be reached. Never a reason to refuse a player. */
        UNKNOWN
    }

    private record CachedAnswer(Answer answer, long expiresAt) {

        boolean hasExpired() {
            return System.currentTimeMillis() >= this.expiresAt;
        }
    }
}
