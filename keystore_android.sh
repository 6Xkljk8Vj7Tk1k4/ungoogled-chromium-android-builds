#!/usr/bin/env bash
# Własny klucz podpisu APK Androida.
#
# Klucz żyje POZA repo (~/.keystores). `seal` zapisuje jego zaszyfrowaną kopię
# w secrets/android-keystore-bundle.tar.aes. Hasło do kopii trzymaj
# w menedżerze haseł.
#
# Katalog secrets/ jest w .gitignore i NIE MOŻE trafić do publicznego repo:
# każdy mógłby tam łamać hasło kopii bez ograniczeń. Trzymaj go w prywatnej
# kopii albo poza gitem.
#
#   ./keystore_android.sh generate     # nowy klucz RSA 4096 (30 lat) w ~/.keystores
#   ./keystore_android.sh seal         # szyfruje ~/.keystores do kopii w repo
#   ./keystore_android.sh verify       # czy kopia się odszyfrowuje i ma ten sam odcisk
#   ./keystore_android.sh restore      # odtwarza ~/.keystores z kopii (po utracie maszyny)
#   ./keystore_android.sh fingerprint  # SHA-256 certyfikatu (małe litery, bez dwukropków)
#
# `seal` uruchamia UŻYTKOWNIK: hasło do kopii jest jego. Utrata klucza oznacza,
# że żadna następna wersja nie zainstaluje się na wierzch obecnej (każde
# urządzenie musiałoby odinstalować przeglądarkę i stracić profil).
#
# Nazwa w certyfikacie jest neutralna: certyfikat widzi każdy, kto pobierze APK.
#
# Katalog z kluczem nadpisywalny zmienną KEYSTORE_DIR.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BLOB="$REPO/secrets/android-keystore-bundle.tar.aes"
DEST="${KEYSTORE_DIR:-$HOME/.keystores}"
KEY_NAME="chromium-android-release"
KEYSTORE="$KEY_NAME.keystore"
PASSFILE="$KEY_NAME.pass"
# Widoczna dla każdego, kto pobierze APK. Bez "O=Chromium": to nie jest klucz
# projektu Chromium i nie powinien na taki wyglądać.
DN="CN=Unofficial Chromium Android Builds"
# 30 lat ważności.
VALIDITY_DAYS=10950
# 600k iteracji PBKDF2 -- blob jest czytelny dla każdego, kto ma dostęp do repo,
# więc jedyną realną ochroną jest siła hasła i koszt jego łamania.
ENC_ARGS=(-aes-256-cbc -pbkdf2 -iter 600000 -md sha256)
CMD="${1:-}"

# Wczytuje hasło do zmiennej PASSPHRASE i eksportuje ją dla openssl.
#
# Hasło przekazujemy przez -pass env:, a nie -pass pass:, bo argumenty procesu są
# widoczne w `ps` dla innych użytkowników systemu.
#
# Czytamy jawnie z /dev/tty, a nie ze stdin: te funkcje bywają wywoływane w potoku
# (tar | openssl), gdzie stdin to strumień danych — `read` pożarłby wtedy dane
# zamiast czekać na hasło i zaszyfrował blob śmieciem.
read_passphrase() {
    if [[ -n "${KEYSTORE_PASSPHRASE:-}" ]]; then
        PASSPHRASE="$KEYSTORE_PASSPHRASE"
    else
        PASSPHRASE=""
        # Nieudany `read` (EOF, brak terminala) zwraca kod !=0, co przy `set -e`
        # ubiłoby skrypt BEZ ŻADNEGO komunikatu. Dlatego obsługujemy to jawnie.
        if ! read -rsp "Hasło do blobu: " PASSPHRASE < /dev/tty; then
            echo >&2
            echo "Nie udało się wczytać hasła z terminala." >&2
            echo "Uruchom z podaniem hasła: KEYSTORE_PASSPHRASE='...' $0 $CMD" >&2
            exit 1
        fi
        echo >&2
    fi
    if [[ -z "$PASSPHRASE" ]]; then
        echo "Puste hasło — przerywam." >&2
        exit 1
    fi
    export PASSPHRASE
}

# $1 = katalog zawierający keystore i plik z jego hasłem.
# Wypisuje SHA-256 certyfikatu małymi literami, bez dwukropków -- w tej samej
# postaci, w jakiej podaje go `apksigner verify --print-certs`.
# Hasło idzie przez -storepass:file, bo argumenty procesu widać w `ps`.
fingerprint() {
    keytool -list -v -keystore "$1/$KEYSTORE" -storepass:file "$1/$PASSFILE" 2>/dev/null \
        | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1 | tr -d ':' | tr 'A-F' 'a-f'
}

unpack_to() {  # $1 = katalog docelowy
    openssl enc -d "${ENC_ARGS[@]}" -pass env:PASSPHRASE -in "$BLOB" | tar -C "$1" -xf -
}

case "${1:-}" in
generate)
    if [[ -f "$DEST/$KEYSTORE" ]]; then
        echo "Klucz istnieje już w $DEST/$KEYSTORE -- nie nadpisuję." >&2
        echo "Odcisk: $(fingerprint "$DEST")"
        exit 0
    fi
    umask 077
    mkdir -p "$DEST"
    # Losowe hasło do keystore'a -- trafia do pliku $PASSFILE (600), nie do repo.
    # Najpierw plik, potem keytool z -storepass:file: hasło nie pojawia się
    # w argumentach procesu.
    openssl rand -base64 24 | tr -d '\n' > "$DEST/$PASSFILE"
    if ! keytool -genkeypair \
        -keystore "$DEST/$KEYSTORE" -storetype PKCS12 \
        -alias "$KEY_NAME" \
        -keyalg RSA -keysize 4096 \
        -validity "$VALIDITY_DAYS" \
        -dname "$DN" \
        -storepass:file "$DEST/$PASSFILE"; then
        rm -f "$DEST/$KEYSTORE" "$DEST/$PASSFILE"
        echo "BŁĄD: keytool nie wygenerował klucza." >&2
        exit 1
    fi
    chmod 600 "$DEST/$KEYSTORE" "$DEST/$PASSFILE"
    echo "Wygenerowano klucz w $DEST/$KEYSTORE (RSA 4096, $VALIDITY_DAYS dni)."
    echo "Odcisk: $(fingerprint "$DEST")"
    echo "Teraz: $0 seal  (uruchamia UŻYTKOWNIK -- hasło do kopii jest jego)"
    ;;
restore)
    [[ -f "$BLOB" ]] || { echo "Brak $BLOB" >&2; exit 1; }
    read_passphrase
    umask 077
    mkdir -p "$DEST"
    unpack_to "$DEST"
    chmod 600 "$DEST/$KEYSTORE" "$DEST/$PASSFILE"
    echo "Odtworzono w $DEST"
    fingerprint "$DEST"
    ;;
seal)
    [[ -f "$DEST/$KEYSTORE" ]] || { echo "Brak klucza w $DEST" >&2; exit 1; }
    read_passphrase
    mkdir -p "$REPO/secrets"
    tar -C "$DEST" -cf - "$KEYSTORE" "$PASSFILE" \
        | openssl enc "${ENC_ARGS[@]}" -salt -pass env:PASSPHRASE -out "$BLOB"

    # Natychmiastowy test odszyfrowania. Bez niego błąd w szyfrowaniu wyszedłby
    # dopiero przy odzyskiwaniu klucza, czyli w najgorszym możliwym momencie.
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
    if ! unpack_to "$tmp" 2>/dev/null; then
        rm -f "$BLOB"
        echo "BŁĄD: zapisany blob nie daje się odszyfrować. Usunięto go." >&2
        exit 1
    fi
    if [[ "$(fingerprint "$tmp")" != "$(fingerprint "$DEST")" ]]; then
        rm -f "$BLOB"
        echo "BŁĄD: odcisk z blobu nie zgadza się z oryginałem. Usunięto go." >&2
        exit 1
    fi
    echo "Zaszyfrowano do $BLOB"
    echo "Sprawdzone — odzyskany odcisk: $(fingerprint "$tmp")"
    ;;
verify)
    [[ -f "$BLOB" ]] || { echo "Brak $BLOB" >&2; exit 1; }
    read_passphrase
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
    unpack_to "$tmp"
    echo "z blobu:  $(fingerprint "$tmp")"
    if [[ -f "$DEST/$KEYSTORE" ]]; then echo "lokalnie: $(fingerprint "$DEST")"; fi
    ;;
fingerprint)
    [[ -f "$DEST/$KEYSTORE" ]] || { echo "Brak klucza w $DEST" >&2; exit 1; }
    fp="$(fingerprint "$DEST")"
    [[ "$fp" =~ ^[0-9a-f]{64}$ ]] || { echo "BŁĄD: nie da się odczytać odcisku (złe hasło w $PASSFILE?)" >&2; exit 1; }
    echo "$fp"
    ;;
*)
    sed -n '2,23p' "$0"; exit 1 ;;
esac
