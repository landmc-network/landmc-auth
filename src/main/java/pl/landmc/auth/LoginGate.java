package pl.landmc.auth;

import com.eternalcode.multification.notice.provider.NoticeProvider;
import com.eternalcode.multification.shared.Formatter;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import pl.landmc.auth.config.AuthConfig;
import pl.landmc.auth.config.AuthMessages;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/**
 * What happens the moment a player stops waiting: they are told so, and they are moved off the
 * limbo onto a real server.
 *
 * <p>Lives on its own because three different things finish a login - a password, a
 * registration, and a session or premium account that skipped both - and all three have to do
 * exactly the same thing afterwards. Duplicating "and then send them to the lobby" three times
 * is how one of them ends up forgetting.
 */
public final class LoginGate {

    private final ProxyServer proxy;
    private final AuthConfig config;
    private final AuthMessages messages;
    private final VelocityNoticeService<AuthMessages> notices;
    private final Logger logger;

    public LoginGate(
            ProxyServer proxy,
            AuthConfig config,
            AuthMessages messages,
            VelocityNoticeService<AuthMessages> notices,
            Logger logger) {

        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Greets a player who has just authenticated and moves them on.
     *
     * <p>{@code {PLAYER}} is registered here rather than at each call site. Every greeting is
     * addressed to the player receiving it, and the one that was written without a formatter
     * reached a real client reading "Witaj ponownie, {PLAYER}!".
     */
    public void admit(Player player, NoticeProvider<AuthMessages> greeting) {
        this.notices.create()
                .viewer(player)
                .notice(greeting)
                .formatter(new Formatter().register("{PLAYER}", player.getUsername()))
                .send();

        // Clears the "log in to continue" title that has been on screen the whole time.
        player.clearTitle();

        this.sendToLobby(player);
    }

    /**
     * Moves a player from the limbo to the lobby.
     *
     * <p>A player who is already somewhere else is left alone: a session login can complete
     * before the initial server has been chosen, and connecting a player who is mid-connection
     * to a second server is how you get one stuck between the two.
     */
    public void sendToLobby(Player player) {
        Optional<RegisteredServer> lobby = this.proxy.getServer(this.config.lobbyServer);
        if (lobby.isEmpty()) {
            this.logger.error(
                    "Lobby server '{}' is not registered; {} has nowhere to go after logging in.",
                    this.config.lobbyServer, player.getUsername());
            return;
        }

        boolean onLimbo = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName().equals(this.config.limboServer))
                .orElse(false);

        if (!onLimbo) {
            return;
        }

        player.createConnectionRequest(lobby.get()).fireAndForget();
    }

    public AuthMessages messages() {
        return this.messages;
    }
}
