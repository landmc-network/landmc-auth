package pl.landmc.auth.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import java.util.Objects;
import org.slf4j.Logger;
import pl.landmc.auth.Account;
import pl.landmc.auth.AuthService;
import pl.landmc.auth.config.AuthMessages;
import pl.landmc.platform.component.ComponentFormatter;

/**
 * Decides, before the connection is encrypted, whether Mojang should be asked about this player.
 *
 * <p>This is the only moment the choice can be made. Once the login has begun, whether the
 * client was asked to authenticate is settled, and the answer has to differ per player:
 * somebody who turned premium on expects to walk straight in, and somebody who did not has to
 * be let through without Mojang ever being consulted, or they could not join at all.
 *
 * <p>Which costs one database read per connection attempt. It is kept - see
 * {@link AuthService#preload(String)} - so the rest of the connection needs none.
 */
public final class PreLoginListener {

    private final AuthService auth;
    private final AuthMessages messages;
    private final ComponentFormatter formatter;
    private final Logger logger;

    public PreLoginListener(
            AuthService auth,
            AuthMessages messages,
            ComponentFormatter formatter,
            Logger logger) {

        this.auth = Objects.requireNonNull(auth, "auth");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            // Something already refused this connection - a ban, or the anti-proxy. Reading an
            // account for a player who is not getting in is a query for nothing.
            return null;
        }

        String name = event.getUsername();

        return EventTask.resumeWhenComplete(this.auth.preload(name).handle((account, throwable) -> {
            if (throwable != null) {
                // Refusing here rather than guessing. Falling back to offline mode would let
                // anybody in under a premium player's name the moment the database hiccups;
                // falling back to online mode would lock out every other player at once.
                this.logger.error("Could not read the account of {} before login", name, throwable);
                event.setResult(PreLoginComponentResult.denied(
                        this.formatter.format(this.messages.accountUnavailableScreen)));
                return null;
            }

            event.setResult(account.map(Account::premium).orElse(false)
                    ? PreLoginComponentResult.forceOnlineMode()
                    : PreLoginComponentResult.forceOfflineMode());
            return null;
        }));
    }
}
