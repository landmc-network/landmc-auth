package pl.landmc.auth.config;

import com.eternalcode.multification.notice.Notice;
import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.CustomKey;
import java.time.Duration;
import pl.landmc.platform.config.message.PlatformMessagesConfig;

/**
 * {@code messages.yml} - everything a player is told while they are not yet logged in.
 *
 * <p>The two headings, LOGOWANIE and REJESTRACJA, are the ones this network has used since its
 * first version, and they carry real information: they tell a player at a glance whether the
 * server thinks they already have an account. Keeping them means a returning player recognises
 * the screen rather than wondering whether they are on the right address.
 *
 * <p>Nothing here ever reveals whether a name is registered in response to a wrong password.
 * "That account does not exist" and "that password is wrong" have to read the same, because the
 * difference between them is exactly what somebody working through a list of names is after.
 */
public class AuthMessages extends OkaeriConfig {

    @Comment("Komunikaty techniczne wspolne dla calej sieci - dostarcza je landmc-platform.")
    public PlatformMessagesConfig platform = new PlatformMessagesConfig();

    // --- prompts -------------------------------------------------------------------------

    @Comment("")
    @Comment("Przypomnienie dla gracza, ktory ma konto i musi podac haslo.")
    @CustomKey("login-prompt")
    public Notice loginPrompt = Notice.builder()
            .chat("<green><bold>LOGOWANIE</bold> <gray>Zaloguj się komendą <green>/zaloguj [hasło]")
            .actionBar("<gray>Zaloguj się: <green>/zaloguj [hasło]")
            .build();

    @Comment("")
    @Comment("Przypomnienie dla gracza, ktory nie ma jeszcze konta.")
    @CustomKey("register-prompt")
    public Notice registerPrompt = Notice.builder()
            .chat("<green><bold>REJESTRACJA</bold> <gray>Załóż konto komendą"
                    + " <green>/zarejestruj [hasło] [powtórz hasło]")
            .actionBar("<gray>Załóż konto: <green>/zarejestruj [hasło] [powtórz hasło]")
            .build();

    @Comment("")
    @Comment("Tytul widoczny przez caly czas oczekiwania na zalogowanie.")
    @CustomKey("waiting-title")
    public Notice waitingTitle = Notice.builder()
            .title("<green><bold>LANDMC.PL")
            .subtitle("<gray>Zaloguj się, aby wejść na serwer")
            .times(Duration.ofMillis(200), Duration.ofSeconds(30), Duration.ZERO)
            .build();

    // --- logging in ----------------------------------------------------------------------

    @Comment("")
    @Comment("Placeholder: {PLAYER}")
    @CustomKey("login-success")
    public Notice loginSuccess = Notice.builder()
            .chat("<green><bold>LOGOWANIE</bold> <gray>Witaj ponownie, <white>{PLAYER}</white>!")
            .build();

    @Comment("")
    @Comment("Gracz wrocil z tego samego adresu w czasie waznosci sesji i nie podawal hasla.")
    @CustomKey("login-session")
    public Notice loginSession = Notice.builder()
            .chat("<green><bold>LOGOWANIE</bold> <gray>Zalogowano automatycznie <dark_gray>(sesja)")
            .build();

    @Comment("")
    @Comment("Gracz zostal uwierzytelniony przez Mojanga i nie podawal hasla.")
    @CustomKey("login-premium")
    public Notice loginPremium = Notice.builder()
            .chat("<green><bold>LOGOWANIE</bold> <gray>Zalogowano kontem premium.")
            .build();

    @Comment("")
    @Comment("Blednie podane haslo. Placeholder: {ATTEMPTS} - ile prob zostalo.")
    @CustomKey("login-wrong-password")
    public Notice loginWrongPassword = Notice.builder()
            .chat("<red>Błąd> <gray>Nieprawidłowe hasło. Pozostałe próby: <white>{ATTEMPTS}</white>.")
            .build();

    @Comment("")
    @Comment("Gracz uzyl /zaloguj, bedac juz zalogowanym.")
    @CustomKey("already-logged-in")
    public Notice alreadyLoggedIn = Notice.builder()
            .chat("<red>Błąd> <gray>Jesteś już zalogowany.")
            .build();

    @Comment("")
    @Comment("Gracz uzyl /zaloguj, nie majac konta.")
    @CustomKey("not-registered")
    public Notice notRegistered = Notice.builder()
            .chat("<red>Błąd> <gray>Nie masz jeszcze konta. Użyj"
                    + " <white>/zarejestruj [hasło] [powtórz hasło]</white>.")
            .build();

    // --- registering ---------------------------------------------------------------------

    @Comment("")
    @CustomKey("register-success")
    public Notice registerSuccess = Notice.builder()
            .chat("<green><bold>REJESTRACJA</bold> <gray>Konto założone. Zapamiętaj hasło -"
                    + " <white>nikt z obsługi nigdy o nie nie zapyta</white>.")
            .build();

    @Comment("")
    @CustomKey("already-registered")
    public Notice alreadyRegistered = Notice.builder()
            .chat("<red>Błąd> <gray>Ten nick jest już zarejestrowany. Użyj <white>/zaloguj [hasło]</white>.")
            .build();

    @Comment("")
    @CustomKey("passwords-do-not-match")
    public Notice passwordsDoNotMatch = Notice.builder()
            .chat("<red>Błąd> <gray>Podane hasła nie są identyczne.")
            .build();

    @Comment("")
    @Comment("Placeholder: {MINIMUM}")
    @CustomKey("password-too-short")
    public Notice passwordTooShort = Notice.builder()
            .chat("<red>Błąd> <gray>Hasło musi mieć co najmniej <white>{MINIMUM}</white> znaków.")
            .build();

    @Comment("")
    @Comment("Placeholder: {MAXIMUM}")
    @CustomKey("password-too-long")
    public Notice passwordTooLong = Notice.builder()
            .chat("<red>Błąd> <gray>Hasło może mieć najwyżej <white>{MAXIMUM}</white> znaków.")
            .build();

    @Comment("")
    @Comment("Haslo z listy zakazanych albo identyczne z nickiem.")
    @CustomKey("password-too-common")
    public Notice passwordTooCommon = Notice.builder()
            .chat("<red>Błąd> <gray>To hasło jest zbyt oczywiste. Wybierz inne.")
            .build();

    // --- changing a password -------------------------------------------------------------

    @Comment("")
    @CustomKey("password-changed")
    public Notice passwordChanged = Notice.builder()
            .chat("<green><bold>LOGOWANIE</bold> <gray>Hasło zostało zmienione.")
            .build();

    @Comment("")
    @CustomKey("password-change-wrong")
    public Notice passwordChangeWrong = Notice.builder()
            .chat("<red>Błąd> <gray>Podane dotychczasowe hasło jest nieprawidłowe.")
            .build();

    // --- premium -------------------------------------------------------------------------

    @Comment("")
    @CustomKey("premium-enabled")
    public Notice premiumEnabled = Notice.builder()
            .chat("<green><bold>LOGOWANIE</bold> <gray>Logowanie kontem premium włączone."
                    + "<newline><gray>Przy następnym wejściu nie będziesz podawać hasła.")
            .build();

    @Comment("")
    @CustomKey("premium-disabled")
    public Notice premiumDisabled = Notice.builder()
            .chat("<green><bold>LOGOWANIE</bold> <gray>Logowanie kontem premium wyłączone."
                    + "<newline><gray>Od teraz znowu logujesz się hasłem.")
            .build();

    @Comment("")
    @CustomKey("premium-already")
    public Notice premiumAlready = Notice.builder()
            .chat("<red>Błąd> <gray>Logowanie kontem premium jest już włączone.")
            .build();

    @Comment("")
    @CustomKey("premium-not-enabled")
    public Notice premiumNotEnabled = Notice.builder()
            .chat("<red>Błąd> <gray>Nie masz włączonego logowania kontem premium.")
            .build();

    @Comment("")
    @Comment("Gracz chce wylaczyc premium, ale nie ma hasla, ktorym moglby sie potem zalogowac.")
    @CustomKey("premium-needs-password")
    public Notice premiumNeedsPassword = Notice.builder()
            .chat("<red>Błąd> <gray>Najpierw ustaw hasło komendą <white>/zmienhaslo</white>,"
                    + " inaczej stracisz dostęp do konta.")
            .build();

    @Comment("")
    @Comment("Nick nie istnieje w Mojangu, wiec wlaczenie premium zablokowaloby konto na stale.")
    @CustomKey("premium-not-a-mojang-account")
    public Notice premiumNotAMojangAccount = Notice.builder()
            .chat("<red>Błąd> <gray>Nick <white>{PLAYER}</white> nie jest kontem premium."
                    + "<newline><gray>Gdybyś to włączył, nie wszedłbyś już na serwer.")
            .build();

    // --- administration ------------------------------------------------------------------

    @Comment("")
    @Comment("Placeholdery: {PLAYER}, {STATE}")
    @CustomKey("admin-premium-set")
    public Notice adminPremiumSet = Notice.builder()
            .chat("<green><bold>LOGOWANIE</bold> <gray>Logowanie premium dla <white>{PLAYER}</white>:"
                    + " <white>{STATE}</white>.")
            .build();

    @Comment("")
    @Comment("Placeholder: {PLAYER}")
    @CustomKey("admin-unknown-account")
    public Notice adminUnknownAccount = Notice.builder()
            .chat("<red>Błąd> <gray>Gracz <white>{PLAYER}</white> nie ma konta.")
            .build();

    @Comment("")
    @CustomKey("premium-disabled-on-server")
    public Notice premiumDisabledOnServer = Notice.builder()
            .chat("<red>Błąd> <gray>Logowanie kontem premium jest wyłączone na tym serwerze.")
            .build();

    // --- blocked while not logged in -----------------------------------------------------

    @Comment("")
    @Comment("Odpowiedz na kazda inna komende i na czat przed zalogowaniem.")
    @CustomKey("must-authenticate")
    public Notice mustAuthenticate = Notice.builder()
            .chat("<red>Błąd> <gray>Najpierw musisz się zalogować.")
            .build();

    @Comment("")
    public Notice failed = Notice.builder()
            .chat("<red>Błąd> <gray>Coś poszło nie tak. Spróbuj ponownie za chwilę.")
            .build();

    // --- disconnect screens --------------------------------------------------------------

    @Comment("")
    @Comment("Ekran rozlaczenia po przekroczeniu czasu na zalogowanie. Placeholder: {SECONDS}")
    @CustomKey("timeout-screen")
    public String timeoutScreen =
            "<green><bold>LANDMC.PL</bold>"
                    + "<newline><newline><gray>Nie zalogowałeś się w ciągu <white>{SECONDS}</white> sekund."
                    + "<newline><gray>Wejdź ponownie i spróbuj jeszcze raz.";

    @Comment("")
    @Comment("Ekran rozlaczenia po wyczerpaniu prob podania hasla.")
    @CustomKey("attempts-screen")
    public String attemptsScreen =
            "<green><bold>LANDMC.PL</bold>"
                    + "<newline><newline><gray>Zbyt wiele nieudanych prób logowania."
                    + "<newline><gray>Jeśli to nie Ty - Twoje konto jest bezpieczne,"
                    + "<newline><gray>ale ktoś próbował się na nie dostać.";

    @Comment("")
    @Comment("Ekran rozlaczenia, gdy limbo jest niedostepne i nie ma gdzie czekac na logowanie.")
    @CustomKey("limbo-unavailable-screen")
    public String limboUnavailableScreen =
            "<green><bold>LANDMC.PL</bold>"
                    + "<newline><newline><gray>Serwer logowania jest chwilowo niedostępny."
                    + "<newline><gray>Spróbuj ponownie za chwilę.";

    @Comment("")
    @Comment("Ekran rozlaczenia, gdy nie udalo sie odczytac konta z bazy.")
    @CustomKey("account-unavailable-screen")
    public String accountUnavailableScreen =
            "<green><bold>LANDMC.PL</bold>"
                    + "<newline><newline><gray>Nie udało się odczytać Twojego konta."
                    + "<newline><gray>Spróbuj ponownie za chwilę.";
}
