package pl.landmc.auth.command;

import com.eternalcode.multification.notice.Notice;
import com.eternalcode.multification.shared.Formatter;
import com.velocitypowered.api.proxy.Player;
import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import java.util.Objects;
import com.eternalcode.multification.notice.provider.NoticeProvider;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import pl.landmc.auth.Account;
import pl.landmc.auth.AuthService;
import pl.landmc.auth.LoginGate;
import pl.landmc.auth.PendingLogin;
import pl.landmc.auth.config.AuthConfig;
import pl.landmc.auth.config.AuthMessages;
import pl.landmc.auth.premium.PremiumLookup;
import pl.landmc.platform.component.ComponentFormatter;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/**
 * The commands a player uses to prove who they are, and to change how they do it.
 *
 * <p>Every command name here has to appear in {@link pl.landmc.auth.listener.GuardListener}'s
 * allow list if a waiting player is meant to be able to run it - and only those. The test
 * {@code AllowedCommandsTest} fails when the two drift apart, which they otherwise would the
 * first time somebody adds an alias.
 */
public final class AuthCommands {

    private AuthCommands() {
    }

    /** {@code /zaloguj <hasło>} */
    @Command(name = "zaloguj", aliases = {"login", "l"})
    public static class Login extends AuthCommand {

        public Login(
                AuthService auth,
                LoginGate gate,
                VelocityNoticeService<AuthMessages> notices,
                ComponentFormatter formatter,
                AuthConfig config,
                Logger logger) {

            super(auth, gate, notices, formatter, config, logger);
        }

        @Execute
        void execute(@Context Player player, @Arg("hasło") String password) {
            PendingLogin pending = this.auth.pending(player.getUniqueId());
            if (pending == null) {
                this.notice(player, messages -> messages.alreadyLoggedIn);
                return;
            }

            if (!pending.isRegistered()) {
                this.notice(player, messages -> messages.notRegistered);
                return;
            }

            this.auth.login(pending, password).thenAccept(result -> {
                switch (result) {
                    case SUCCESS -> this.gate.admit(player, messages -> messages.loginSuccess);
                    case WRONG_PASSWORD -> this.notices.create()
                            .viewer(player)
                            .notice(messages -> messages.loginWrongPassword)
                            .formatter(new Formatter().register(
                                    "{ATTEMPTS}", Integer.toString(pending.attemptsLeft())))
                            .send();
                    case OUT_OF_ATTEMPTS -> {
                        this.logger.warn(
                                "{} ran out of login attempts from {}.",
                                player.getUsername(), pending.address());
                        player.disconnect(this.screen(messages -> messages.attemptsScreen));
                    }
                    // The player typed twice while the first check was still running; the
                    // answer to the first one is on its way and will say what happened.
                    case BUSY -> {
                    }
                    case NOT_REGISTERED -> this.notice(player, messages -> messages.notRegistered);
                    case FAILED -> this.notice(player, messages -> messages.failed);
                }
            });
        }
    }

    /** {@code /zarejestruj <hasło> <powtórz hasło>} */
    @Command(name = "zarejestruj", aliases = {"register", "reg", "rejestracja"})
    public static class Register extends AuthCommand {

        public Register(
                AuthService auth,
                LoginGate gate,
                VelocityNoticeService<AuthMessages> notices,
                ComponentFormatter formatter,
                AuthConfig config,
                Logger logger) {

            super(auth, gate, notices, formatter, config, logger);
        }

        @Execute
        void execute(
                @Context Player player,
                @Arg("hasło") String password,
                @Arg("powtórz hasło") String confirmation) {

            PendingLogin pending = this.auth.pending(player.getUniqueId());
            if (pending == null) {
                this.notice(player, messages -> messages.alreadyLoggedIn);
                return;
            }

            this.auth.register(pending, password, confirmation).thenAccept(result -> {
                switch (result) {
                    case SUCCESS -> {
                        this.logger.info(
                                "{} registered from {}.", player.getUsername(), pending.address());
                        this.gate.admit(player, messages -> messages.registerSuccess);
                    }
                    case ALREADY_REGISTERED -> this.notice(player, messages -> messages.alreadyRegistered);
                    case MISMATCH -> this.notice(player, messages -> messages.passwordsDoNotMatch);
                    case TOO_SHORT -> this.notices.create()
                            .viewer(player)
                            .notice(messages -> messages.passwordTooShort)
                            .formatter(new Formatter().register(
                                    "{MINIMUM}", Integer.toString(this.config.password.minimumLength)))
                            .send();
                    case TOO_LONG -> this.notices.create()
                            .viewer(player)
                            .notice(messages -> messages.passwordTooLong)
                            .formatter(new Formatter().register(
                                    "{MAXIMUM}", Integer.toString(this.config.password.maximumLength)))
                            .send();
                    case TOO_COMMON -> this.notice(player, messages -> messages.passwordTooCommon);
                    case FAILED -> this.notice(player, messages -> messages.failed);
                }
            });
        }
    }

    /**
     * {@code /zmienhaslo <obecne> <nowe> <powtórz nowe>}
     *
     * <p>The current password is required. The previous server asked for fifty diamonds
     * instead, which meant anybody who reached an unattended client owned the account.
     */
    @Command(name = "zmienhaslo", aliases = {"changepassword", "nowehaslo"})
    public static class ChangePassword extends AuthCommand {

        public ChangePassword(
                AuthService auth,
                LoginGate gate,
                VelocityNoticeService<AuthMessages> notices,
                ComponentFormatter formatter,
                AuthConfig config,
                Logger logger) {

            super(auth, gate, notices, formatter, config, logger);
        }

        @Execute
        void execute(
                @Context Player player,
                @Arg("obecne hasło") String current,
                @Arg("nowe hasło") String replacement,
                @Arg("powtórz nowe hasło") String confirmation) {

            this.withAccount(player, account ->
                    this.auth.changePassword(account, current, replacement, confirmation)
                            .thenAccept(result -> {
                                switch (result) {
                                    case SUCCESS -> {
                                        this.logger.info("{} changed their password.", player.getUsername());
                                        this.notice(player, messages -> messages.passwordChanged);
                                    }
                                    case WRONG_PASSWORD ->
                                            this.notice(player, messages -> messages.passwordChangeWrong);
                                    case MISMATCH ->
                                            this.notice(player, messages -> messages.passwordsDoNotMatch);
                                    case TOO_SHORT -> this.notices.create()
                                            .viewer(player)
                                            .notice(messages -> messages.passwordTooShort)
                                            .formatter(new Formatter().register(
                                                    "{MINIMUM}",
                                                    Integer.toString(this.config.password.minimumLength)))
                                            .send();
                                    case TOO_LONG -> this.notices.create()
                                            .viewer(player)
                                            .notice(messages -> messages.passwordTooLong)
                                            .formatter(new Formatter().register(
                                                    "{MAXIMUM}",
                                                    Integer.toString(this.config.password.maximumLength)))
                                            .send();
                                    case TOO_COMMON ->
                                            this.notice(player, messages -> messages.passwordTooCommon);
                                    case FAILED -> this.notice(player, messages -> messages.failed);
                                }
                            }));
        }
    }

    /**
     * {@code /premium} - stop being asked for a password and let Mojang vouch instead.
     *
     * <p>Only a player who is already logged in can turn this on, which is what makes it safe:
     * the account is proved with the password first, and only then does it start trusting
     * Mojang. Turning it on for a name the player does not actually own simply locks them out
     * of it, and nobody else in.
     */
    @Command(name = "premium")
    public static class Premium extends AuthCommand {

        private final PremiumLookup lookup;

        public Premium(
                AuthService auth,
                LoginGate gate,
                VelocityNoticeService<AuthMessages> notices,
                ComponentFormatter formatter,
                AuthConfig config,
                PremiumLookup lookup,
                Logger logger) {

            super(auth, gate, notices, formatter, config, logger);
            this.lookup = Objects.requireNonNull(lookup, "lookup");
        }

        @Execute
        void execute(@Context Player player) {
            if (!this.config.premium.enabled) {
                this.notice(player, messages -> messages.premiumDisabledOnServer);
                return;
            }

            this.withAccount(player, account -> {
                if (account.premium()) {
                    this.notice(player, messages -> messages.premiumAlready);
                    return;
                }

                if (!this.config.premium.verifyWithMojang) {
                    this.enable(player, account);
                    return;
                }

                this.lookup.lookup(player.getUsername()).thenAccept(answer -> {
                    if (answer == PremiumLookup.Answer.NOT_PREMIUM) {
                        // The one thing worth refusing: this player would lock themselves out
                        // and could not run the command that undoes it.
                        this.logger.info(
                                "Refused /premium for {}: Mojang does not know that name.",
                                player.getUsername());
                        this.notices.create()
                                .viewer(player)
                                .notice(messages -> messages.premiumNotAMojangAccount)
                                .formatter(new Formatter().register("{PLAYER}", player.getUsername()))
                                .send();
                        return;
                    }

                    // UNKNOWN means Mojang did not answer. Refusing then would keep every
                    // premium player waiting on somebody else's outage.
                    this.enable(player, account);
                });
            });
        }

        private void enable(Player player, Account account) {
            this.auth.setPremium(account.name(), true)
                    .thenRun(() -> {
                        this.logger.info("{} turned premium login on.", player.getUsername());
                        this.notice(player, messages -> messages.premiumEnabled);
                    })
                    .exceptionally(this.report(player, "premium on"));
        }
    }

    /** {@code /niepremium} - go back to logging in with a password. */
    @Command(name = "niepremium", aliases = {"nopremium"})
    public static class NoPremium extends AuthCommand {

        public NoPremium(
                AuthService auth,
                LoginGate gate,
                VelocityNoticeService<AuthMessages> notices,
                ComponentFormatter formatter,
                AuthConfig config,
                Logger logger) {

            super(auth, gate, notices, formatter, config, logger);
        }

        @Execute
        void execute(@Context Player player) {
            this.withAccount(player, account -> {
                if (!account.premium()) {
                    this.notice(player, messages -> messages.premiumNotEnabled);
                    return;
                }

                if (!account.hasPassword()) {
                    // Their account has never had a password, so turning Mojang off would leave
                    // nothing at all that could get them back in.
                    this.notice(player, messages -> messages.premiumNeedsPassword);
                    return;
                }

                this.auth.setPremium(account.name(), false)
                        .thenRun(() -> {
                            this.logger.info("{} turned premium login off.", player.getUsername());
                            this.notice(player, messages -> messages.premiumDisabled);
                        })
                        .exceptionally(this.report(player, "premium off"));
            });
        }
    }

    /** What every command here shares: the services, and the two ways of answering a player. */
    abstract static class AuthCommand {

        protected final AuthService auth;
        protected final LoginGate gate;
        protected final VelocityNoticeService<AuthMessages> notices;
        protected final ComponentFormatter formatter;
        protected final AuthConfig config;
        protected final Logger logger;

        AuthCommand(
                AuthService auth,
                LoginGate gate,
                VelocityNoticeService<AuthMessages> notices,
                ComponentFormatter formatter,
                AuthConfig config,
                Logger logger) {

            this.auth = Objects.requireNonNull(auth, "auth");
            this.gate = Objects.requireNonNull(gate, "gate");
            this.notices = Objects.requireNonNull(notices, "notices");
            this.formatter = Objects.requireNonNull(formatter, "formatter");
            this.config = Objects.requireNonNull(config, "config");
            this.logger = Objects.requireNonNull(logger, "logger");
        }

        protected void notice(Player player, NoticeProvider<AuthMessages> which) {
            this.notices.create().viewer(player).notice(which).send();
        }

        protected Component screen(Function<AuthMessages, String> which) {
            return this.formatter.format(which.apply(this.gate.messages()));
        }

        /**
         * Reads the player's account and hands it to the caller.
         *
         * <p>Re-read rather than cached: these commands change the account, and two of them run
         * one after another would otherwise act on a copy that is already stale.
         */
        protected void withAccount(Player player, Consumer<Account> work) {
            this.auth.account(player.getUsername())
                    .thenAccept(account -> {
                        if (account.isEmpty()) {
                            this.notice(player, messages -> messages.notRegistered);
                            return;
                        }
                        work.accept(account.get());
                    })
                    .exceptionally(this.report(player, "account lookup"));
        }

        protected Function<Throwable, Void> report(Player player, String what) {
            return throwable -> {
                this.logger.error("Auth command failed ({}) for {}", what, player.getUsername(), throwable);
                this.notice(player, messages -> messages.failed);
                return null;
            };
        }
    }
}
