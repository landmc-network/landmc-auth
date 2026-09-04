package pl.landmc.auth;

import com.eternalcode.multification.shared.Formatter;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import pl.landmc.auth.config.AuthConfig;
import pl.landmc.auth.config.AuthMessages;
import pl.landmc.platform.component.ComponentFormatter;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/**
 * Nags waiting players, and disconnects the ones who never answer.
 *
 * <p>One repeating task for the whole proxy, not one per player. A thousand players joining
 * would otherwise be a thousand scheduled tasks, and the work each of them does - compare two
 * longs - is far cheaper than the scheduling around it.
 *
 * <p>It only ever walks the map of players who are still waiting, which is empty on a normal
 * server most of the time and never holds anybody who has logged in.
 */
public final class LoginWatchdog {

    /** How often the sweep runs. Fine enough for a timeout quoted in whole seconds. */
    private static final Duration INTERVAL = Duration.ofSeconds(1);

    /**
     * How long a pre-login result waits for the post-login that should claim it.
     *
     * <p>Generous: a slow client on a bad connection still has to get through encryption and
     * the Mojang round trip. Anything older than this belonged to a connection that died.
     */
    private static final Duration PRELOAD_MAX_AGE = Duration.ofMinutes(2);

    private final AuthService auth;
    private final ProxyServer proxy;
    private final AuthConfig config;
    private final AuthMessages messages;
    private final VelocityNoticeService<AuthMessages> notices;
    private final ComponentFormatter formatter;
    private final Logger logger;

    /** Counts seconds so the reminder does not need a second task with a second period. */
    private int tick;

    public LoginWatchdog(
            AuthService auth,
            ProxyServer proxy,
            AuthConfig config,
            AuthMessages messages,
            VelocityNoticeService<AuthMessages> notices,
            ComponentFormatter formatter,
            Logger logger) {

        this.auth = Objects.requireNonNull(auth, "auth");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Schedules the sweep. Returns the task so the plugin can cancel it on shutdown. */
    public com.velocitypowered.api.scheduler.ScheduledTask start(Object plugin) {
        return this.proxy.getScheduler()
                .buildTask(plugin, this::sweep)
                .repeat(INTERVAL)
                .schedule();
    }

    void sweep() {
        long now = System.currentTimeMillis();

        for (PendingLogin login : this.auth.expired(now)) {
            this.proxy.getPlayer(login.playerId()).ifPresent(player -> {
                this.logger.info(
                        "{} did not log in within {} seconds.",
                        player.getUsername(), this.config.timeoutSeconds);

                player.disconnect(this.formatter.format(
                        this.messages.timeoutScreen.replace(
                                "{SECONDS}", Integer.toString(this.config.timeoutSeconds))));
            });
        }

        this.auth.sweepPreloaded(now, PRELOAD_MAX_AGE);

        int every = Math.max(1, this.config.reminderSeconds);
        if (++this.tick % every != 0) {
            return;
        }

        for (PendingLogin login : this.auth.waiting()) {
            Optional<Player> player = this.proxy.getPlayer(login.playerId());
            if (player.isEmpty()) {
                continue;
            }

            this.notices.create()
                    .viewer(player.get())
                    .notice(messages -> login.isRegistered()
                            ? messages.loginPrompt
                            : messages.registerPrompt)
                    .formatter(new Formatter().register(
                            "{ATTEMPTS}", Integer.toString(login.attemptsLeft())))
                    .send();
        }
    }
}
