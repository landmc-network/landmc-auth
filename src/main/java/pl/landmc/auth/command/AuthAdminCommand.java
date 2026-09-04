package pl.landmc.auth.command;

import com.eternalcode.multification.shared.Formatter;
import com.velocitypowered.api.command.CommandSource;
import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import java.util.Objects;
import org.slf4j.Logger;
import pl.landmc.auth.AuthService;
import pl.landmc.auth.config.AuthMessages;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/**
 * {@code /auth premium <gracz> <true|false>} - turns Mojang login on or off for somebody else.
 *
 * <p>This exists because {@code /premium} is a door that locks behind the player. Once the flag
 * is on, the proxy demands Mojang authentication from that account, so a player who turned it on
 * by mistake - or whose Microsoft account they have lost - cannot connect at all, and therefore
 * cannot run the command that would undo it. Without something like this the only fix is an
 * {@code UPDATE} typed into the production database by hand.
 */
@Command(name = "auth")
@Permission("landmc.auth.admin")
public final class AuthAdminCommand {

    private final AuthService auth;
    private final VelocityNoticeService<AuthMessages> notices;
    private final Logger logger;

    public AuthAdminCommand(
            AuthService auth, VelocityNoticeService<AuthMessages> notices, Logger logger) {

        this.auth = Objects.requireNonNull(auth, "auth");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Execute(name = "premium")
    void premium(
            @Context CommandSource sender,
            @Arg("gracz") String playerName,
            @Arg("premium") boolean premium) {

        this.auth.account(playerName).thenAccept(account -> {
            if (account.isEmpty()) {
                this.notices.create()
                        .viewer(sender)
                        .notice(messages -> messages.adminUnknownAccount)
                        .formatter(new Formatter().register("{PLAYER}", playerName))
                        .send();
                return;
            }

            this.auth.setPremium(account.get().name(), premium).thenRun(() -> {
                // Worth a log line: it changes how somebody proves who they are, and the next
                // question when an account is disputed is who changed it and when.
                this.logger.info(
                        "{} set premium login for {} to {}.",
                        describe(sender), account.get().displayName(), premium);

                this.notices.create()
                        .viewer(sender)
                        .notice(messages -> messages.adminPremiumSet)
                        .formatter(new Formatter()
                                .register("{PLAYER}", account.get().displayName())
                                .register("{STATE}", premium ? "włączone" : "wyłączone"))
                        .send();
            });
        }).exceptionally(throwable -> {
            this.logger.error("Could not change premium login for {}", playerName, throwable);
            this.notices.create().viewer(sender).notice(messages -> messages.failed).send();
            return null;
        });
    }

    private static String describe(CommandSource sender) {
        return sender instanceof com.velocitypowered.api.proxy.Player player
                ? player.getUsername()
                : "Konsola";
    }
}
