package pl.landmc.auth.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.CustomKey;
import pl.landmc.platform.database.DatabaseConfig;

/** {@code config.yml} - how a player proves who they are before they reach a real server. */
public class AuthConfig extends OkaeriConfig {

    @Comment("Serwer, na ktorym gracz czeka na zalogowanie. Musi byc zdefiniowany w velocity.toml.")
    @Comment("To pusty limbo bez swiata: gracz niezalogowany nie ma czego dotknac.")
    @CustomKey("limbo-server")
    public String limboServer = "limbo";

    @Comment("")
    @Comment("Serwer, na ktory trafia gracz po zalogowaniu.")
    @CustomKey("lobby-server")
    public String lobbyServer = "lobby";

    @Comment("")
    @Comment("Ile sekund gracz ma na zalogowanie lub rejestracje, zanim zostanie wyrzucony.")
    @Comment("Stary serwer dawal 30 sekund i to sie sprawdzalo.")
    @CustomKey("timeout-seconds")
    public int timeoutSeconds = 60;

    @Comment("")
    @Comment("Co ile sekund gracz dostaje przypomnienie, ze ma sie zalogowac.")
    @CustomKey("reminder-seconds")
    public int reminderSeconds = 5;

    @Comment("")
    @Comment("Ile razy mozna pomylic haslo, zanim polaczenie zostanie zerwane.")
    @Comment("Nie chroni to konta samo w sobie - chroni przed zgadywaniem hasla w petli.")
    @CustomKey("max-attempts")
    public int maxAttempts = 5;

    @Comment("")
    public PasswordSection password = new PasswordSection();

    @Comment("")
    public SessionSection session = new SessionSection();

    @Comment("")
    public PremiumSection premium = new PremiumSection();

    @Comment("")
    @Comment("Baza z kontami. Dane logowania czyta z plugins/landmc-shared.yml lub ze zmiennych srodowiskowych.")
    public DatabaseConfig database = new DatabaseConfig();

    /** Wymagania wobec hasla. */
    public static class PasswordSection extends OkaeriConfig {

        @Comment("Minimalna dlugosc hasla.")
        @CustomKey("minimum-length")
        public int minimumLength = 6;

        @Comment("Maksymalna dlugosc hasla. Kazda proba logowania kosztuje procesor,")
        @Comment("wiec bardzo dlugie haslo jest sposobem na obciazenie serwera.")
        @CustomKey("maximum-length")
        public int maximumLength = 64;

        @Comment("")
        @Comment("Liczba rund PBKDF2. Wieksza wartosc to wolniejsze logowanie i wolniejsze lamanie.")
        @Comment("Po zmianie stare hasla nadal dzialaja - sa przeliczane przy pierwszym poprawnym logowaniu.")
        public int iterations = 210_000;

        @Comment("")
        @Comment("Hasla odrzucane niezaleznie od dlugosci. Wpisane malymi literami.")
        @CustomKey("forbidden-passwords")
        public java.util.List<String> forbiddenPasswords = java.util.List.of(
                "haslo", "password", "123456", "qwerty", "minecraft", "landmc");
    }

    /** Ponowne wejscie z tego samego adresu bez podawania hasla. */
    public static class SessionSection extends OkaeriConfig {

        @Comment("Czy gracz, ktory wroci z tego samego adresu, ma pominac logowanie.")
        public boolean enabled = true;

        @Comment("")
        @Comment("Jak dlugo sesja jest wazna od rozlaczenia, w minutach.")
        @CustomKey("minutes")
        public int minutes = 15;
    }

    /** Logowanie kontem Mojanga zamiast haslem. */
    public static class PremiumSection extends OkaeriConfig {

        @Comment("Czy gracze moga wlaczyc sobie logowanie przez Mojanga komenda /premium.")
        public boolean enabled = true;

        @Comment("")
        @Comment("Czy przed wlaczeniem sprawdzac w Mojangu, ze taki nick w ogole istnieje.")
        @Comment("Gracz bez konta premium, ktory wlaczy sobie /premium, nie wejdzie juz na serwer")
        @Comment("i nie moze tego cofnac sam - to jest jedyne zabezpieczenie przed pomylka.")
        @Comment("Gdy Mojang nie odpowiada, komenda przechodzi - awaria po ich stronie nie moze")
        @Comment("blokowac graczy, ktorzy faktycznie maja premium.")
        @CustomKey("verify-with-mojang")
        public boolean verifyWithMojang = true;
    }
}
