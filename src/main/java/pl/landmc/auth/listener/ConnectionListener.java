package pl.landmc.auth.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import pl.landmc.auth.Addresses;
import pl.landmc.auth.AuthService;
import pl.landmc.auth.LoginGate;
import pl.landmc.auth.config.AuthConfig;
import pl.landmc.auth.config.AuthMessages;
import pl.landmc.platform.component.ComponentFormatter;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/**
 * Where a player goes while they are not logged in, and what is remembered when they leave.
 *
 * <p>The rule is that an unauthenticated player is only ever on the limbo. Not on the lobby
 * with their movement cancelled, which is what the previous server did - that approach means
 * every listener on the lobby, now and for ever, has to remember to check whether the player
 * it is looking at has proved who they are. One of them eventually will not.
 */
public final class ConnectionListener {

    private final AuthService auth;
    private final LoginGate gate;
    private final ProxyServer proxy;
    private final AuthConfig config;
    private final AuthMessages messages;
    private final VelocityNoticeService<AuthMessages> notices;
    private final ComponentFormatter formatter;
    private final Logger logger;

    public ConnectionListener(
            AuthService auth,
            LoginGate gate,
            ProxyServer proxy,
            AuthConfig config,
            AuthMessages messages,
            VelocityNoticeService<AuthMessages> notices,
            ComponentFormatter formatter,
            Logger logger) {

        this.auth = Objects.requireNonNull(auth, "auth");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Decides what this player still has to do, and tells them.
     *
     * <p>Returned as an {@link EventTask} so the proxy waits for it. The decision has to be
     * made before the initial server is chosen: a player whose state is still being worked out
     * when {@code PlayerChooseInitialServerEvent} fires would be routed as though they were
     * logged in.
     */
    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        String address = Addresses.of(player.getRemoteAddress());

        return EventTask.resumeWhenComplete(this.auth
                .begin(player.getUniqueId(), player.getUsername(), address, player.isOnlineMode())
                .handle((outcome, throwable) -> {
                    if (throwable != null) {
                        this.logger.error(
                                "Could not start the login of {}", player.getUsername(), throwable);
                        player.disconnect(this.formatter.format(this.messages.accountUnavailableScreen));
                        return null;
                    }

                    switch (outcome) {
                        case PREMIUM -> this.gate.admit(player, messages -> messages.loginPremium);
                        case SESSION -> this.gate.admit(player, messages -> messages.loginSession);
                        case MUST_LOG_IN -> this.prompt(player, false);
                        case MUST_REGISTER -> this.prompt(player, true);
                    }
                    return null;
                }));
    }

    private void prompt(Player player, boolean register) {
        this.notices.create()
                .viewer(player)
                .notice(messages -> messages.waitingTitle)
                .notice(messages -> register ? messages.registerPrompt : messages.loginPrompt)
                .send();
    }

    /**
     * Sends a waiting player to the limbo instead of wherever they were going.
     *
     * <p>Runs last on purpose. The routing in landmc-proxy sets the initial server too, and
     * whichever handler runs last is the one that decides; this one has to be able to override
     * a perfectly reasonable choice that happens to be wrong for a player who is not logged in.
     */
    @Subscribe(priority = Short.MIN_VALUE)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        if (this.auth.isAuthenticated(player.getUniqueId())) {
            return;
        }

        Optional<RegisteredServer> limbo = this.proxy.getServer(this.config.limboServer);
        if (limbo.isEmpty()) {
            // There is nowhere to hold this player, and the alternative - letting them onto the
            // lobby unauthenticated - is the thing this plugin exists to prevent.
            this.logger.error(
                    "Limbo server '{}' is not registered; refusing the connection of {}.",
                    this.config.limboServer, player.getUsername());
            player.disconnect(this.formatter.format(this.messages.limboUnavailableScreen));
            return;
        }

        event.setInitialServer(limbo.get());
    }

    /**
     * Keeps a waiting player on the limbo, and a logged-in player off it.
     *
     * <p>The second half is not symmetry for its own sake. Velocity fails a player over to the
     * server they came from when a backend drops them, and for somebody who has just logged in
     * that is the limbo - where they would sit in an empty world with nothing to interact with
     * and no command that moves them, because the plugin only ever moves a player at the moment
     * they authenticate. Refusing the connection instead lets the proxy tell them what happened.
     */
    @Subscribe(priority = Short.MIN_VALUE)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String target = event.getOriginalServer().getServerInfo().getName();
        boolean limbo = target.equals(this.config.limboServer);

        if (this.auth.isAuthenticated(player.getUniqueId())) {
            if (limbo) {
                this.logger.debug(
                        "Refusing to send {} back to the limbo: already logged in.",
                        player.getUsername());
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
            return;
        }

        if (limbo) {
            return;
        }

        this.logger.debug(
                "Refusing to send {} to {}: not logged in.", player.getUsername(), target);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }

    /** Forgets the connection, and records a session if it was a logged-in one. */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();

        this.auth.onDisconnect(
                player.getUniqueId(),
                player.getUsername(),
                Addresses.of(player.getRemoteAddress()));
    }
}
