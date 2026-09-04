package pl.landmc.auth.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.landmc.platform.component.ComponentFormatter;
import pl.landmc.platform.config.ConfigService;
import pl.landmc.platform.proxy.notice.VelocityNoticeService;

/**
 * Every {@code {PLACEHOLDER}} in a message has to be one something actually fills in.
 *
 * <p>This exists because of a real bug: {@code login-success} read "Witaj ponownie, {PLAYER}!"
 * and was sent without a formatter, so the first player to log in successfully was greeted by
 * name as "{PLAYER}". Nothing failed - not the build, not a test, not the proxy - because an
 * unsubstituted placeholder is a perfectly valid string.
 *
 * <p>So the check is on the message file rather than on the code: a placeholder that nothing
 * knows how to fill cannot be added without this failing and the author deciding what fills it.
 */
class MessagePlaceholderTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Z_]+)}");

    /**
     * The placeholders this plugin substitutes, and where.
     *
     * <p>Adding to this list means adding a {@code Formatter#register} to match; the point of
     * the list is that the two decisions happen together.
     */
    private static final Set<String> SUBSTITUTED = Set.of(
            // LoginGate#admit, for every greeting.
            "PLAYER",
            // AuthCommands.Login, on a wrong password.
            "ATTEMPTS",
            // AuthCommands, when a password is refused for its length.
            "MINIMUM",
            "MAXIMUM",
            // LoginWatchdog, on the timeout disconnect screen.
            "SECONDS",
            // AuthAdminCommand, reporting what it set.
            "STATE");

    @Test
    @DisplayName("no message contains a placeholder that nothing fills in")
    void everyPlaceholderIsSubstituted(@TempDir Path directory) throws IOException {
        Set<String> unknown = new TreeSet<>();

        for (String line : Files.readAllLines(generateMessages(directory))) {
            Matcher matcher = PLACEHOLDER.matcher(line);
            while (matcher.find()) {
                if (!SUBSTITUTED.contains(matcher.group(1))) {
                    unknown.add(matcher.group(1) + " in: " + line.trim());
                }
            }
        }

        assertTrue(unknown.isEmpty(), "messages.yml uses placeholders nothing substitutes: " + unknown);
    }

    private static Path generateMessages(Path directory) {
        AuthMessages[] holder = new AuthMessages[1];

        VelocityNoticeService<AuthMessages> notices = new VelocityNoticeService<>(
                unusableProxy(), locale -> holder[0], ComponentFormatter.standard());

        ConfigService configs = new ConfigService(notices.okaeriSerdes());
        holder[0] = configs.load(directory, "messages.yml", AuthMessages.class);

        return directory.resolve("messages.yml");
    }

    private static ProxyServer unusableProxy() {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class},
                (instance, method, args) -> {
                    throw new UnsupportedOperationException(
                            "Configuration loading must not call ProxyServer#" + method.getName());
                });
    }
}
