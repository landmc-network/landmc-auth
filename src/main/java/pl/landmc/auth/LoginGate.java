package pl.landmc.auth;

import com.eternalcode.multification.notice.provider.NoticeProvider;
import com.eternalcode.multification.shared.Formatter;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Greetings waiting for their player to arrive on the lobby.
     *
     * <p>Concurrent because a login finishes on a proxy thread and the arrival is announced on
     * another. An entry lives for the length of one server switch; a player who never arrives
     * has theirs dropped when they disconnect.
     */
    private final Map<UUID, NoticeProvider<AuthMessages>> pending = new ConcurrentHashMap<>();

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
     * <p>The greeting is held until they have arrived, not sent before they are moved. A client
     * that changes server goes through a configuration phase and starts the new one with an
     * empty chat, so a message sent a moment earlier is written to a window that is about to be
     * thrown away - which is why logging in looked silent even though the message was on the
     * wire. {@link #greet} sends it once they are there.
     *
     * <p>{@code {PLAYER}} is registered in one place rather than at each call site. Every
     * greeting is addressed to the player receiving it, and the one that was written without a
     * formatter reached a real client reading "Witaj ponownie, {PLAYER}!".
     */
    public void admit(Player player, NoticeProvider<AuthMessages> greeting) {
        // Clears the "log in to continue" title that has been on screen the whole time.
        player.clearTitle();

        boolean settled = player.getCurrentServer()
                .map(connection -> !connection.getServerInfo().getName().equals(this.config.limboServer))
                .orElse(false);

        if (settled) {
            // Already somewhere real, with no switch coming to lose the message in.
            this.greet(player, greeting);
            return;
        }

        // Either standing on the limbo about to be moved, or - for a session or premium login,
        // which finishes during PostLoginEvent - not yet on any server at all. Both end with an
        // arrival, and the greeting waits for it.
        this.pending.put(player.getUniqueId(), greeting);
        this.sendToLobby(player);
    }

    /**
     * Sends the greeting a player was admitted with, if they were still owed one.
     *
     * <p>Called when they arrive somewhere. Removing the entry as it is read means a second
     * server switch does not greet them again.
     */
    public void greet(Player player) {
        NoticeProvider<AuthMessages> greeting = this.pending.remove(player.getUniqueId());
        if (greeting != null) {
            this.greet(player, greeting);
        }
    }

    /** Forgets a greeting nobody is going to receive. */
    public void forget(UUID playerId) {
        this.pending.remove(playerId);
    }

    private void greet(Player player, NoticeProvider<AuthMessages> greeting) {
        this.notices.viewer(
                player, greeting, new Formatter().register("{PLAYER}", player.getUsername()));
    }

    /**
     * Moves a player from the limbo to the lobby.
     *
     * <p>A player who is already somewhere else is left alone: a session login can complete
     * before the initial server has been chosen, and connecting a player who is mid-connection
     * to a second server is how you get one stuck between the two.
     *
     * @return whether a move was actually asked for, so the caller knows whether an arrival is
     *     coming that anything held back can wait for
     */
    public boolean sendToLobby(Player player) {
        Optional<RegisteredServer> lobby = this.proxy.getServer(this.config.lobbyServer);
        if (lobby.isEmpty()) {
            this.logger.error(
                    "Lobby server '{}' is not registered; {} has nowhere to go after logging in.",
                    this.config.lobbyServer, player.getUsername());
            return false;
        }

        boolean onLimbo = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName().equals(this.config.limboServer))
                .orElse(false);

        if (!onLimbo) {
            return false;
        }

        player.createConnectionRequest(lobby.get()).fireAndForget();
        return true;
    }

    public AuthMessages messages() {
        return this.messages;
    }
}
