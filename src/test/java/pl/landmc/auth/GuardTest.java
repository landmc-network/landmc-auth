package pl.landmc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rollczi.litecommands.annotations.command.Command;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.landmc.auth.command.AuthAdminCommand;
import pl.landmc.auth.command.AuthCommands;
import pl.landmc.auth.listener.GuardListener;

/**
 * What a player who has not logged in is allowed to do.
 *
 * <p>The allow list and the commands are declared in two different files, and the failure mode
 * when they disagree is silent in both directions: an alias that is registered but not allowed
 * simply stops working for the people who need it most, and one allowed but not registered is a
 * gap somebody could look for.
 */
class GuardTest {

    /** The commands a waiting player is meant to be able to reach. */
    private static final List<Class<?>> UNAUTHENTICATED_COMMANDS =
            List.of(AuthCommands.Login.class, AuthCommands.Register.class);

    /** Everything else this plugin registers - reachable only once logged in. */
    private static final List<Class<?>> AUTHENTICATED_COMMANDS = List.of(
            AuthCommands.ChangePassword.class,
            AuthCommands.Premium.class,
            AuthCommands.NoPremium.class,
            AuthAdminCommand.class);

    @Test
    @DisplayName("the allow list is exactly the names of the login and register commands")
    void allowListMatchesTheCommands() {
        Set<String> declared = new TreeSet<>();
        for (Class<?> command : UNAUTHENTICATED_COMMANDS) {
            declared.addAll(namesOf(command));
        }

        assertEquals(
                declared,
                new TreeSet<>(GuardListener.ALLOWED_COMMANDS),
                "the guard's allow list and the commands it is meant to allow have drifted apart");
    }

    @Test
    @DisplayName("nothing a logged-in player uses is reachable before logging in")
    void doesNotAllowTheOtherCommands() {
        for (Class<?> command : AUTHENTICATED_COMMANDS) {
            for (String name : namesOf(command)) {
                assertFalse(
                        GuardListener.isAllowed(name),
                        name + " can be run without logging in");
            }
        }
    }

    @Test
    @DisplayName("the allowed commands are recognised however the player types them")
    void recognisesWhatPlayersActuallyType() {
        assertTrue(GuardListener.isAllowed("zaloguj tajneHaslo"));
        assertTrue(GuardListener.isAllowed("/zaloguj tajneHaslo"));
        assertTrue(GuardListener.isAllowed("ZaLoGuJ tajneHaslo"));
        assertTrue(GuardListener.isAllowed("  zaloguj  tajneHaslo  "));
        assertTrue(GuardListener.isAllowed("l tajneHaslo"));
        assertTrue(GuardListener.isAllowed("zarejestruj haslo haslo"));

        // Velocity resolves a namespaced command to the same handler, so the guard has to see
        // through the namespace or it becomes a way around itself.
        assertTrue(GuardListener.isAllowed("landmc-auth:zaloguj tajneHaslo"));
        assertTrue(GuardListener.isAllowed("/landmc-auth:zaloguj tajneHaslo"));
    }

    @Test
    @DisplayName("everything else is blocked, including near misses")
    void blocksEverythingElse() {
        assertFalse(GuardListener.isAllowed("server lobby"));
        assertFalse(GuardListener.isAllowed("/server lobby"));
        assertFalse(GuardListener.isAllowed("zalogujsie haslo"));
        assertFalse(GuardListener.isAllowed("zaloguj2"));
        assertFalse(GuardListener.isAllowed(""));
        assertFalse(GuardListener.isAllowed("/"));
        assertFalse(GuardListener.isAllowed("premium"));
        assertFalse(GuardListener.isAllowed("zmienhaslo stare nowe nowe"));
        assertFalse(GuardListener.isAllowed("auth premium Crispi false"));
    }

    /** The name and every alias of a LiteCommands command class. */
    private static Set<String> namesOf(Class<?> command) {
        Command annotation = command.getAnnotation(Command.class);
        if (annotation == null) {
            throw new AssertionError(command.getSimpleName() + " is not annotated with @Command");
        }

        Set<String> names = new HashSet<>();
        names.add(annotation.name());
        names.addAll(Arrays.stream(annotation.aliases()).collect(Collectors.toSet()));
        return names;
    }
}
