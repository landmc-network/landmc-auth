# LandMC Auth

Logowanie i rejestracja dla całej sieci, na proxy. Gracz, który się nie zalogował, nie stoi
nigdzie, gdzie mógłby cokolwiek zrobić.

Zbudowane na [`landmc-platform`](https://github.com/landmc-network/landmc-platform).

## Trzy decyzje, z których wynika reszta

**Niezalogowany gracz siedzi na limbo, nie na lobby.** Stary LandMC trzymał go na lobby
i anulował mu ruch, czat, klikanie i ekwipunek — co oznacza, że każdy listener napisany kiedykolwiek
dla lobby musi pamiętać o sprawdzeniu, czy gracz jest zalogowany. Któryś w końcu nie zapamięta.
Limbo to osobny proces bez świata i bez pluginów: nie ma tam czego sprawdzać.

**Czy pytamy Mojanga, decyduje konto — nie serwer.** `online-mode` na proxy jest wyłączony,
a połączenie jest podnoszone do trybu online w `PreLoginEvent` tylko dla kont, których właściciel
sam włączył `/premium`. Dzięki temu premium jest wygodą, którą się włącza, a nie sposobem na
wejście na cudzy nick.

**Każdy gracz ma zawsze to samo UUID.** To jest kawałek, na którym stoją dwa poprzednie.
Gracz w trybie offline dostaje UUID wyliczone z nicku, a uwierzytelniony przez Mojanga —
zupełnie inne, wystawione przez Mojanga. Cała reszta sieci (kary, znajomi, vouchery, ekonomia)
kluczuje po UUID, więc bez tego włączenie `/premium` sprawiałoby, że gracz staje się dla sieci
kimś, kogo nigdy tu nie było: bez rangi, bez kasy, bez znajomych — i bez bana.
`GameProfileListener` przepisuje więc profil z powrotem na UUID offline, zostawiając właściwości
premium (skórkę i jej podpis).

Kosztem tej decyzji jest to, że sieć nie może już przejść na UUID Mojanga bez przepisania każdej
tabeli, która je trzyma. To realny koszt — i wciąż tańsza strona: alternatywa gubi dane gracza
w dniu, w którym zmienia sposób logowania.

## Komendy

| | |
|---|---|
| `/zaloguj <hasło>` | aliasy `login`, `l` |
| `/zarejestruj <hasło> <powtórz>` | aliasy `register`, `reg`, `rejestracja` |
| `/zmienhaslo <obecne> <nowe> <powtórz>` | wymaga obecnego hasła |
| `/premium` | od teraz uwierzytelnia Mojang, nie hasło |
| `/niepremium` | z powrotem na hasło |
| `/auth premium <gracz> <true\|false>` | `landmc.auth.admin` |

Przed zalogowaniem działają wyłącznie `/zaloguj` i `/zarejestruj` — lista jest w
`GuardListener`, a test pilnuje, żeby nie rozjechała się z tym, co faktycznie zarejestrowane.

## Hasła

PBKDF2-HMAC-SHA512 z JDK, 210 000 rund (OWASP), losowa sól na hasło. Algorytm i liczba rund są
zapisane w samej wartości, więc podniesienie kosztu nie unieważnia niczego: stare hasło nadal się
weryfikuje i jest po cichu przeliczane przy pierwszym poprawnym logowaniu.

Konta zaimportowane ze starego serwera (`sha256$<hex>`, bez soli, jedna runda) logują się raz
i też są przeliczane. Nic nie zapisuje tego formatu — jest tylko do odczytu.

Hashowanie ma własną pulę wątków, o połowie rdzeni maszyny. Nie bazodanową — bo logowania
ustawiłyby się w kolejce za swoją arytmetyką — i na pewno nie wątek Netty.

## Sesje

Powrót z tego samego adresu w ciągu 15 minut pomija hasło. Sesje żyją w pamięci proxy: sesja jest
warta dokładnie jeden pominięty prompt, a restart proxy i tak rozłącza wszystkich. Trzymanie ich
w bazie oznaczałoby, że przejęta sesja przeżywa proces, który ją wydał.

Zmiana hasła kasuje sesję — zmiana hasła to zwykle ktoś, kto odzyskuje swoje konto.

## Czego komunikaty nie mówią

Złe hasło i nieistniejące konto brzmią tak samo, bo różnica między nimi jest dokładnie tym,
czego szuka ktoś przelatujący listę nicków.

## `/premium` to drzwi, które zamykają się za graczem

Po włączeniu proxy żąda od tego konta uwierzytelnienia przez Mojanga — więc gracz, który włączył
je przez pomyłkę, nie wejdzie i nie wpisze komendy, która to cofa. Stąd dwie rzeczy: przed
włączeniem sprawdzamy w Mojangu, czy taki nick w ogóle istnieje (gdy Mojang nie odpowiada,
komenda przechodzi — cudza awaria nie może blokować graczy), oraz `/auth premium <gracz> false`
dla administracji.

## Build

```bash
cd ../landmc-platform && ./gradlew publishToMavenLocal -Pversion=1.2.0
```

```bash
./gradlew build
```

Wymaga limbo w `velocity.toml` — konfiguracja i obraz są w
[`landmc-deploy`](https://github.com/landmc-network/landmc-deploy).

## Czym różni się od oryginału

Przeniesione z `LoginPlugin` starego LandMC, z którego zostały funkcje i kolorystyka:

- **hasła solone i kosztowne** zamiast gołego SHA-256, którego jedna leaknięta tabela łamie się
  kartą graficzną w popołudnie;
- **`/zmienhaslo` pyta o obecne hasło.** Oryginał brał 50 diamentów i nie pytał o nic — kto
  usiadł przy cudzym kliencie, przejmował konto na stałe;
- **hasło może mieć znaki inne niż litery i cyfry.** Oryginał wymagał `^[a-zA-Z0-9]*$`,
  co odrzuca każde mocne hasło i przepuszcza puste;
- **limbo zamiast anulowania eventów** na lobby;
- **limit prób i okno logowania** liczone na proxy, nie zadaniem na gracza;
- **logowanie premium** i stabilne UUID, których w oryginale nie było wcale.

## Czego tu jeszcze nie ma

- odzyskiwania hasła (mail, Discord) — nie ma jeszcze do czego tego podpiąć;
- dwuskładnikowego logowania dla administracji;
- sesji współdzielonych między proxy — przy jednym proxy nie mają znaczenia.
