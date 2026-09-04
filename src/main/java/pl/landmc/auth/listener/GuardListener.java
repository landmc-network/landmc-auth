package pl.landmc.auth.listener;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import pl.landmc.auth.AuthService;
import pl.landmc.auth.config.AuthMessages;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/**
 * Stops a player who has not logged in from doing anything except logging in.
 *
 * <p>Both handlers here run for every command and every chat message on the proxy, from
 * everybody, logged in or not. So the first thing either does is one lookup in a hash set, and
 * for the overwhelming majority of traffic - players who logged in minutes ago - that is the
 * whole cost.
 */
public final class GuardListener {

    /**
     * The commands a waiting player may still use.
     *
     * <p>Held here rather than derived from what is registered, because the check has to be
     * exact: a command that is allowed by accident is a command an unauthenticated player can
     * run. Kept in step with {@link pl.landmc.auth.command.AuthCommands} by the test that
     * compares the two.
     */
    public static final Set<String> ALLOWED_COMMANDS = Set.of(
            "zaloguj", "login", "l",
            "zarejestruj", "register", "reg", "rejestracja");

    private final AuthService auth;
    private final VelocityNoticeService<AuthMessages> notices;

    public GuardListener(AuthService auth, VelocityNoticeService<AuthMessages> notices) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.notices = Objects.requireNonNull(notices, "notices");
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onCommand(CommandExecuteEvent event) {
        CommandSource source = event.getCommandSource();
        if (!(source instanceof Player player) || this.auth.isAuthenticated(player.getUniqueId())) {
            return;
        }

        if (isAllowed(event.getCommand())) {
            return;
        }

        event.setResult(CommandExecuteEvent.CommandResult.denied());
        this.notices.create().viewer(player).notice(messages -> messages.mustAuthenticate).send();
    }

    /**
     * Blocks chat until the player is in.
     *
     * <p>Not only because an unauthenticated player has nothing to say. A player who forgets
     * the slash types their password into the chat box, and a chat message that is passed on is
     * a password read by everybody who happens to be looking.
     *
     * <p>{@code setResult} is deprecated because a modern client signs its chat, and a signed
     * message cannot be withheld from the backend without disconnecting the player. So this is
     * best effort, and it is not what actually protects the password: the player is on the
     * limbo, which has no chat handling and nobody else on it, so a message that does slip
     * through is read by nothing. The call stays because it still holds for anything that is
     * not signed, and costs nothing when it does not.
     */
    @SuppressWarnings("deprecation")
    @Subscribe(priority = Short.MAX_VALUE)
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (this.auth.isAuthenticated(player.getUniqueId())) {
            return;
        }

        event.setResult(PlayerChatEvent.ChatResult.denied());
        this.notices.create().viewer(player).notice(messages -> messages.mustAuthenticate).send();
    }

    /** Whether a command line names one of the commands a waiting player is allowed to run. */
    public static boolean isAllowed(String commandLine) {
        String root = commandLine.trim();

        int space = root.indexOf(' ');
        if (space >= 0) {
            root = root.substring(0, space);
        }
        if (root.startsWith("/")) {
            root = root.substring(1);
        }

        // A player typing "/landmc-auth:zaloguj" is using the same command by its qualified
        // name, and Velocity resolves it to the same handler.
        int namespace = root.indexOf(':');
        if (namespace >= 0) {
            root = root.substring(namespace + 1);
        }

        return ALLOWED_COMMANDS.contains(root.toLowerCase(Locale.ROOT));
    }
}
