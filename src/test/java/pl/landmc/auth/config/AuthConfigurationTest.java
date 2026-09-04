package pl.landmc.auth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.landmc.platform.component.ComponentFormatter;
import pl.landmc.platform.config.ConfigService;
import pl.landmc.platform.notice.PlatformNotice;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/** The two configuration files, loaded the way the plugin's bootstrap loads them. */
class AuthConfigurationTest {

    private static final Pattern KEY = Pattern.compile("^\\s*([A-Za-z0-9_-]+):(\\s|$)");

    @Test
    @DisplayName("both files load, with the defaults the plugin expects")
    void loadsBothFiles(@TempDir Path directory) {
        Loaded loaded = load(directory);

        assertEquals("limbo", loaded.config().limboServer);
        assertEquals("lobby", loaded.config().lobbyServer);
        assertEquals(60, loaded.config().timeoutSeconds);
        assertEquals(5, loaded.config().maxAttempts);
        assertTrue(loaded.config().session.enabled);
        assertTrue(loaded.config().premium.enabled);
        assertEquals(210_000, loaded.config().password.iterations);
    }

    @Test
    @DisplayName("every key is written in kebab-case")
    void writesEveryKeyInKebabCase(@TempDir Path directory) throws IOException {
        // Okaeri names a key after the field unless told otherwise, so a new field arrives as
        // camelCase and sits next to kebab-case neighbours until somebody notices in a running
        // proxy. Checking the whole document means the next @CustomKey cannot be forgotten.
        load(directory);

        for (String fileName : new String[] {"config.yml", "messages.yml"}) {
            for (String line : Files.readAllLines(directory.resolve(fileName))) {
                Matcher key = KEY.matcher(line);
                if (key.find()) {
                    assertEquals(
                            key.group(1).toLowerCase(Locale.ROOT),
                            key.group(1),
                            fileName + " has a camelCase key: " + line.trim());
                }
            }
        }
    }

    @Test
    @DisplayName("the notices survive being written out as YAML")
    void writesNoticeFieldsAsYaml(@TempDir Path directory) throws IOException {
        load(directory);

        String yaml = Files.readString(directory.resolve("messages.yml"));

        assertTrue(yaml.contains("login-prompt:"), yaml);
        assertTrue(yaml.contains("register-prompt:"), yaml);
        assertTrue(yaml.contains("must-authenticate:"), yaml);
        assertTrue(yaml.contains("LOGOWANIE"), yaml);
        assertTrue(yaml.contains("REJESTRACJA"), yaml);
    }

    @Test
    @DisplayName("the shared platform messages come along")
    void embedsThePlatformMessages(@TempDir Path directory) throws IOException {
        Loaded loaded = load(directory);

        assertTrue(Files.readString(directory.resolve("messages.yml")).contains("platform:"));
        assertEquals(
                "<red>Błąd> <gray>Nie posiadasz uprawnień do tej komendy.",
                loaded.messages().platform.message(PlatformNotice.COMMAND_NO_PERMISSION));
    }

    @Test
    @DisplayName("the disconnect screens lead with the domain, as every screen on this network does")
    void kickScreensLeadWithTheDomain(@TempDir Path directory) {
        ComponentFormatter formatter = ComponentFormatter.standard();
        Loaded loaded = load(directory);

        for (String screen : new String[] {
                loaded.messages().timeoutScreen,
                loaded.messages().attemptsScreen,
                loaded.messages().limboUnavailableScreen,
                loaded.messages().accountUnavailableScreen}) {

            String rendered = formatter.plain(formatter.format(screen));
            assertTrue(rendered.startsWith("LANDMC.PL"), rendered);
        }
    }

    @Test
    @DisplayName("the timeout screen quotes the configured number of seconds")
    void timeoutScreenCarriesThePlaceholder(@TempDir Path directory) {
        ComponentFormatter formatter = ComponentFormatter.standard();
        Loaded loaded = load(directory);

        String rendered = formatter.plain(formatter.format(
                loaded.messages().timeoutScreen.replace("{SECONDS}", "60")));

        assertTrue(rendered.contains("60 sekund"), rendered);
    }

    @Test
    @DisplayName("overriding one message leaves the rest at their defaults")
    void overridingOneMessageKeepsTheRest(@TempDir Path directory) throws IOException {
        Files.writeString(
                directory.resolve("messages.yml"),
                "timeout-screen: \"<red>Za wolno.\"\n");

        Loaded loaded = load(directory);

        assertEquals("<red>Za wolno.", loaded.messages().timeoutScreen);
        assertTrue(loaded.messages().attemptsScreen.startsWith("<green><bold>LANDMC.PL"));
    }

    /** Mirrors the bootstrap: the notice service first, then the files it can deserialise. */
    private static Loaded load(Path directory) {
        AuthMessages[] holder = new AuthMessages[1];

        VelocityNoticeService<AuthMessages> notices = new VelocityNoticeService<>(
                unusableProxy(), locale -> holder[0], ComponentFormatter.standard());

        ConfigService configs = new ConfigService(notices.okaeriSerdes());
        AuthConfig config = configs.load(directory, "config.yml", AuthConfig.class);
        holder[0] = configs.load(directory, "messages.yml", AuthMessages.class);

        return new Loaded(config, holder[0]);
    }

    /**
     * A {@code ProxyServer} that throws on every call, so a future change that makes
     * configuration loading depend on a running proxy fails here rather than on startup.
     */
    private static ProxyServer unusableProxy() {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class},
                (instance, method, args) -> {
                    throw new UnsupportedOperationException(
                            "Configuration loading must not call ProxyServer#" + method.getName());
                });
    }

    private record Loaded(AuthConfig config, AuthMessages messages) {
    }
}
