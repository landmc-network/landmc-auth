package pl.landmc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import pl.landmc.auth.premium.PremiumLookup;
import pl.landmc.auth.premium.PremiumLookup.Answer;

/**
 * Against a real HTTP server on localhost rather than a mocked client, because what is worth
 * checking here is how each status code is read - and a stub that returns whatever the test asks
 * for would be checking the test's own assumptions.
 *
 * <p>The behaviour that matters most is the one on failure: this must never answer PREMIUM when
 * it does not know, and must never refuse a player because Mojang was down.
 */
class PremiumLookupTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/profiles/", exchange -> {
            this.requests.incrementAndGet();
            exchange.sendResponseHeaders(this.status, -1);
            exchange.close();
        });
        this.server.start();
    }

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    private PremiumLookup lookup() {
        return new PremiumLookup(NOPLogger.NOP_LOGGER, this.endpoint());
    }

    private String endpoint() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/profiles/";
    }

    @Test
    @DisplayName("200 means the name is a paid account, 404 and 204 mean it is not")
    void readsTheStatusCodes() throws Exception {
        this.status = 200;
        assertEquals(Answer.PREMIUM, await(this.lookup().lookup("Crispi")));

        this.status = 404;
        assertEquals(Answer.NOT_PREMIUM, await(this.lookup().lookup("Crispi")));

        this.status = 204;
        assertEquals(Answer.NOT_PREMIUM, await(this.lookup().lookup("Crispi")));
    }

    @Test
    @DisplayName("anything unexpected is unknown, never a yes")
    void treatsOtherStatusesAsUnknown() throws Exception {
        for (int code : new int[] {429, 500, 503}) {
            this.status = code;
            assertEquals(
                    Answer.UNKNOWN,
                    await(this.lookup().lookup("Crispi")),
                    "status " + code + " was not treated as unknown");
        }
    }

    @Test
    @DisplayName("a server that cannot be reached is unknown, not a refusal")
    void treatsAnOutageAsUnknown() throws Exception {
        this.server.stop(0);

        // Refusing here would mean an outage at Mojang stops every premium player on the
        // network from turning the option on, for as long as it lasts.
        assertEquals(Answer.UNKNOWN, await(this.lookup().lookup("Crispi")));
    }

    @Test
    @DisplayName("an answer is asked for once and then remembered")
    void cachesAnAnswer() throws Exception {
        PremiumLookup lookup = this.lookup();
        this.status = 200;

        assertEquals(Answer.PREMIUM, await(lookup.lookup("Crispi")));
        assertEquals(Answer.PREMIUM, await(lookup.lookup("crispi")));
        assertEquals(Answer.PREMIUM, await(lookup.lookup("CRISPI")));

        assertEquals(1, this.requests.get(), "the same name was looked up more than once");
    }

    @Test
    @DisplayName("a failure is not cached, so an outage does not last longer than it did")
    void doesNotCacheAFailure() throws Exception {
        PremiumLookup lookup = this.lookup();

        this.status = 500;
        assertEquals(Answer.UNKNOWN, await(lookup.lookup("Crispi")));

        this.status = 200;
        assertEquals(Answer.PREMIUM, await(lookup.lookup("Crispi")));
        assertEquals(2, this.requests.get());
    }

    @Test
    @DisplayName("a name that could not be a Minecraft name never reaches the network")
    void refusesAnImpossibleNameWithoutAsking() throws Exception {
        PremiumLookup lookup = this.lookup();

        // The name comes off a connection, so it is checked before it becomes part of a URL.
        assertEquals(Answer.NOT_PREMIUM, await(lookup.lookup("../../admin")));
        assertEquals(Answer.NOT_PREMIUM, await(lookup.lookup("ma spacje")));
        assertEquals(Answer.NOT_PREMIUM, await(lookup.lookup("")));
        assertEquals(Answer.NOT_PREMIUM, await(lookup.lookup("a".repeat(17))));

        assertEquals(0, this.requests.get(), "an impossible name was sent to the endpoint");
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }
}
