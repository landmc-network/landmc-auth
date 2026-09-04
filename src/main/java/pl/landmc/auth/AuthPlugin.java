package pl.landmc.auth;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.rollczi.litecommands.LiteCommands;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import pl.landmc.auth.command.AuthAdminCommand;
import pl.landmc.auth.command.AuthCommands;
import pl.landmc.auth.config.AuthConfig;
import pl.landmc.auth.config.AuthMessages;
import pl.landmc.auth.listener.ConnectionListener;
import pl.landmc.auth.listener.GameProfileListener;
import pl.landmc.auth.listener.GuardListener;
import pl.landmc.auth.listener.PreLoginListener;
import pl.landmc.auth.password.PasswordHasher;
import pl.landmc.auth.premium.PremiumLookup;
import pl.landmc.auth.session.SessionStore;
import pl.landmc.auth.storage.AccountRepository;
import pl.landmc.platform.api.ModuleLifecycle;
import pl.landmc.platform.component.ComponentFormatter;
import pl.landmc.platform.config.ConfigPlaceholders;
import pl.landmc.platform.config.ConfigService;
import pl.landmc.platform.database.DatabaseService;
import pl.landmc.platform.notice.AudienceNoticeService;
import pl.landmc.platform.notice.NoticeServiceProvider;
import pl.landmc.platform.proxy.command.VelocityCommands;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/**
 * Logging in, for the whole network.
 *
 * <p>Three decisions shape everything else.
 *
 * <p>A player who has not logged in is on a limbo - a separate process with no world and no
 * plugins - rather than standing on the lobby with their movement cancelled. The old server did
 * the latter, which turns every listener that will ever be written for the lobby into something
 * that has to remember to check. This way there is nothing there to check.
 *
 * <p>Whether Mojang is asked about a player is decided per account, in the pre-login, from a
 * flag the player sets themselves once they are already logged in. That is what makes premium
 * login an opt-in convenience rather than a way to take somebody else's name.
 *
 * <p>And every player keeps the same UUID either way - see {@link GameProfileListener}, which
 * is the piece the other two depend on.
 */
@Plugin(
        id = "landmc-auth",
        name = "LandMC Auth",
        version = "1.0.0-SNAPSHOT",
        description = "Logowanie i rejestracja sieci LandMC.",
        url = "https://github.com/landmc-network/landmc-auth",
        authors = {"Crispi"})
public final class AuthPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ModuleLifecycle lifecycle;

    private AuthConfig config;
    private AuthMessages messages;
    private DatabaseService database;
    private ExecutorService hashingExecutor;
    private LiteCommands<CommandSource> commands;
    private ScheduledTask watchdog;

    @Inject
    public AuthPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.lifecycle = new ModuleLifecycle(logger);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        ComponentFormatter formatter = ComponentFormatter.standard();

        VelocityNoticeService<AuthMessages> notices =
                new VelocityNoticeService<>(this.proxy, locale -> this.messages, formatter);

        ConfigService configs = new ConfigService(
                ConfigPlaceholders.forPlugin(this.dataDirectory), notices.okaeriSerdes());
        this.config = configs.load(this.dataDirectory, "config.yml", AuthConfig.class);
        this.messages = configs.load(this.dataDirectory, "messages.yml", AuthMessages.class);

        this.database = new DatabaseService(
                "landmc-auth", this.config.database, this.dataDirectory, this.logger);
        this.lifecycle.register(this.database).enableAll();

        AccountRepository accounts = new AccountRepository(this.database);
        accounts.createTables();

        this.hashingExecutor = createHashingExecutor();

        AuthService auth = new AuthService(
                accounts,
                new SessionStore(),
                new PasswordHasher(this.config.password.iterations),
                this.config,
                this.hashingExecutor,
                this.logger);

        LoginGate gate = new LoginGate(this.proxy, this.config, this.messages, notices, this.logger);

        this.proxy.getEventManager().register(
                this, new PreLoginListener(auth, this.messages, formatter, this.logger));
        this.proxy.getEventManager().register(this, new GameProfileListener());
        this.proxy.getEventManager().register(this, new ConnectionListener(
                auth, gate, this.proxy, this.config, this.messages, notices, formatter, this.logger));
        this.proxy.getEventManager().register(this, new GuardListener(auth, notices));

        NoticeServiceProvider<CommandSource> platformNotices =
                new AudienceNoticeService<>(this.messages.platform, formatter);

        this.commands = VelocityCommands.builder(this.proxy, formatter, platformNotices, this.logger)
                .commands(
                        new AuthCommands.Login(auth, gate, notices, formatter, this.config, this.logger),
                        new AuthCommands.Register(auth, gate, notices, formatter, this.config, this.logger),
                        new AuthCommands.ChangePassword(
                                auth, gate, notices, formatter, this.config, this.logger),
                        new AuthCommands.Premium(
                                auth, gate, notices, formatter, this.config,
                                new PremiumLookup(this.logger), this.logger),
                        new AuthCommands.NoPremium(auth, gate, notices, formatter, this.config, this.logger),
                        new AuthAdminCommand(auth, notices, this.logger))
                .build();

        this.watchdog = new LoginWatchdog(
                auth, this.proxy, this.config, this.messages, notices, formatter, this.logger)
                .start(this);

        this.logger.info(
                "Auth ready ({}, limbo '{}', {}s na zalogowanie, premium {}).",
                this.config.database.type,
                this.config.limboServer,
                this.config.timeoutSeconds,
                this.config.premium.enabled ? "włączone" : "wyłączone");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (this.watchdog != null) {
            this.watchdog.cancel();
        }
        if (this.commands != null) {
            this.commands.unregister();
        }
        if (this.hashingExecutor != null) {
            this.hashingExecutor.shutdown();
            try {
                if (!this.hashingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.hashingExecutor.shutdownNow();
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                this.hashingExecutor.shutdownNow();
            }
        }
        this.lifecycle.disableAll();
    }

    /**
     * The pool passwords are hashed on.
     *
     * <p>Its own pool, not the database one and certainly not a Netty thread. Hashing is
     * deliberately expensive - a tenth of a second of pure CPU per attempt - so putting it on
     * the database executor would have logins queueing behind each other's arithmetic, and
     * putting it on a connection thread would stall the proxy's I/O for everybody.
     *
     * <p>Bounded at half the machine's cores: it is the one thing here that can saturate a CPU,
     * and a login burst must not leave the proxy with nothing left to route packets with.
     */
    private static ExecutorService createHashingExecutor() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        return Executors.newFixedThreadPool(threads, new HashingThreadFactory());
    }

    /** Names the hashing threads, so a profiler says which pool is busy. */
    private static final class HashingThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "landmc-auth-hash-" + this.counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
