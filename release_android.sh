#!/usr/bin/env bash
#
# Publikuje APK jako wydanie (Release) w repozytorium na GitHubie, z którego
# wbudowany updater pobiera aktualizacje.
#
#   ./release_android.sh <plik.apk> [--dry-run]
#
# Wszystko, co potrzebne, skrypt czyta z samego APK (znaczniki wpieczone przy
# buildzie), więc nie da się opublikować pliku pod złym tagiem. Przed
# publikacją sprawdza, że:
#   1. APK ma tag wydania (<wersja>-<N>),
#   2. APK szuka aktualizacji w TYM repozytorium, do którego trafia,
#   3. to nie jest build testowy (--update-api-url),
#   4. APK jest podpisany naszym kluczem, a nie publicznym kluczem debug,
#   5. nazwa pliku kończy się na -arm64-v8a.apk (po tym updater go rozpoznaje),
#   6. w APK nie ma prywatnych ciągów z secrets/private_strings.txt.
#
# --dry-run robi wszystkie kontrole i wypisuje polecenie, ale nic nie wysyła.
# Skrypt uruchamia użytkownik: logowanie do GitHuba (`gh auth login`) jest jego.
# Bez `gh` skrypt wypisuje instrukcję dla przeglądarki.
#
# Repozytorium: zmienna UPDATE_REPO albo android_update.conf.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Odcisk build/android/chromium-debug.keystore z drzewa Chromium -- klucz jest
# publiczny, więc APK nim podpisany może podmienić każdy.
DEBUG_CERT_SHA256="32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0"

APK=""
DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -h|--help) sed -n '3,22p' "$0"; exit 0 ;;
        -*) echo "BŁĄD: nieznana opcja '$arg'" >&2; exit 1 ;;
        *) [ -z "$APK" ] || { echo "BŁĄD: podaj jeden plik APK" >&2; exit 1; }; APK="$arg" ;;
    esac
done
[ -n "$APK" ] || { echo "Użycie: $0 <plik.apk> [--dry-run]" >&2; exit 1; }
[ -f "$APK" ] || { echo "BŁĄD: brak pliku '$APK'" >&2; exit 1; }
for tool in unzip strings python3; do
    command -v "$tool" >/dev/null || { echo "BŁĄD: brak narzędzia '$tool'" >&2; exit 1; }
done

UPDATE_REPO="${UPDATE_REPO:-}"
if [ -z "$UPDATE_REPO" ] && [ -f "$HERE/android_update.conf" ]; then
    UPDATE_REPO=$(sed -n 's/^UPDATE_REPO=//p' "$HERE/android_update.conf" | head -1 | tr -d '"'"'")
fi
[ -n "$UPDATE_REPO" ] || { echo "BŁĄD: nie wiadomo, dokąd publikować (UPDATE_REPO w android_update.conf jest puste)" >&2; exit 1; }

# Napisy z plików dex. Potok czytamy do końca (bez `grep -q` i `head`):
# wcześniejsze zamknięcie potoku ubija `unzip` sygnałem SIGPIPE, a przy
# `pipefail` wyglądałoby to jak "nie znaleziono".
DEX_STRINGS=$(unzip -p "$APK" 'classes*.dex' | strings -n 8 | grep -F 'selfupdate-' || true)
# Wypisuje różne wartości znacznika $1 (wzorzec wartości: $2), po jednej w linii.
# Brak trafień to zwykły wynik, nie błąd (grep zwraca wtedy 1, co przy
# `set -e` + `pipefail` kończyłoby skrypt bez słowa).
marker() {
    printf '%s\n' "$DEX_STRINGS" | { grep -o "$1=$2" || true; } | sed "s/^$1=//" | sort -u
}

FAILURES=0
ok()   { echo "  [ok]  $*"; }
fail() { echo "  [BŁĄD] $*" >&2; FAILURES=$((FAILURES + 1)); }

echo "Kontrola $(basename "$APK") przed publikacją w $UPDATE_REPO:"

TAG=$(marker selfupdate-release-tag '[0-9.-]*')
if [[ "$TAG" =~ ^[0-9]+(\.[0-9]+)*-[0-9]+$ ]]; then
    ok "tag wydania: $TAG"
else
    fail "brak jednoznacznego tagu wydania w APK (znalezione: '${TAG//$'\n'/ }') -- to build bez updatera?"
fi

APK_REPO=$(marker selfupdate-repo '[A-Za-z0-9._/-]*')
if [ "$APK_REPO" = "$UPDATE_REPO" ]; then
    ok "APK szuka aktualizacji w $APK_REPO"
else
    fail "APK szuka aktualizacji w '${APK_REPO//$'\n'/ }', a publikacja idzie do '$UPDATE_REPO'"
fi

TEST_API=$(marker selfupdate-test-api '[A-Za-z0-9._:/?=&%-]*')
if [ -z "$TEST_API" ]; then
    ok "build produkcyjny (wydania z GitHuba)"
else
    fail "to build TESTOWY: pobiera wydania z '${TEST_API//$'\n'/ }'"
fi

CERT=$(python3 "$HERE/android_updater/apk_signer_digest.py" "$APK" 2>&1) || true
OUR_CERT=""
if [ -f "${KEYSTORE_DIR:-$HOME/.keystores}/chromium-android-release.keystore" ]; then
    OUR_CERT=$("$HERE/keystore_android.sh" fingerprint 2>/dev/null) || true
fi
if ! [[ "$CERT" =~ ^[0-9a-f]{64}$ ]]; then
    fail "nie da się odczytać certyfikatu podpisu: ${CERT//$'\n'/ }"
elif [ "$CERT" = "$DEBUG_CERT_SHA256" ]; then
    fail "APK jest podpisany publicznym kluczem debug Chromium"
elif [ -z "$OUR_CERT" ]; then
    fail "brak lokalnego klucza do porównania (./keystore_android.sh restore); certyfikat APK: $CERT"
elif [ "$CERT" != "$OUR_CERT" ]; then
    fail "APK jest podpisany innym kluczem ($CERT) niż nasz ($OUR_CERT)"
else
    ok "podpis naszym kluczem: $CERT"
fi

case "$(basename "$APK")" in
    *-arm64-v8a.apk) ok "nazwa pliku kończy się na -arm64-v8a.apk" ;;
    *) fail "nazwa pliku musi kończyć się na -arm64-v8a.apk (po tym updater rozpoznaje plik w wydaniu)" ;;
esac

# Lista prywatnych ciągów leży w secrets/ (katalog, który nigdy nie trafia do
# publicznej kopii repo) -- sama lista zdradzałaby to, co ma chronić.
PRIVATE_LIST="$HERE/secrets/private_strings.txt"
if [ -s "$PRIVATE_LIST" ]; then
    PATTERNS=$(grep -v -E '^[[:space:]]*(#|$)' "$PRIVATE_LIST" || true)
    FOUND=""
    if [ -n "$PATTERNS" ]; then
        # Dwa przebiegi: napisy jednobajtowe (dex, biblioteki) i UTF-16 (manifest, zasoby).
        FOUND=$( { unzip -p "$APK" | strings -n 6 | grep -o -F -e "$PATTERNS" || true
                   unzip -p "$APK" | strings -n 6 -e l | grep -o -F -e "$PATTERNS" || true; } | sort -u )
    fi
    if [ -z "$FOUND" ]; then
        ok "brak prywatnych ciągów ($(printf '%s\n' "$PATTERNS" | grep -c .) wzorców)"
    else
        fail "prywatne ciągi w APK: ${FOUND//$'\n'/, }"
    fi
else
    echo "  [--]  brak $PRIVATE_LIST -- kontrola prywatnych ciągów pominięta"
fi

if [ "$FAILURES" -gt 0 ]; then
    echo "Wydanie ODRZUCONE: nieudanych kontroli: $FAILURES" >&2
    exit 1
fi

# Opis wydania z szablonu: wersja, suma pliku i odcisk certyfikatu.
NOTES_FILE=$(mktemp); trap 'rm -f "$NOTES_FILE"' EXIT
if [ -f "$HERE/android_updater/release_notes.md" ]; then
    sed -e "s/{{VERSION}}/${TAG%-*}/g" -e "s/{{TAG}}/$TAG/g" \
        -e "s/{{SHA256}}/$(sha256sum "$APK" | cut -d' ' -f1)/g" -e "s/{{CERT}}/$CERT/g" \
        "$HERE/android_updater/release_notes.md" > "$NOTES_FILE"
fi

echo
echo "Polecenie:"
echo "  gh release create $TAG $APK --repo $UPDATE_REPO --title $TAG --notes-file <opis>"
echo
echo "Opis wydania:"
sed 's/^/  | /' "$NOTES_FILE"
if [ "$DRY_RUN" -eq 1 ]; then
    echo "(--dry-run: nic nie wysłano)"
    exit 0
fi

if command -v gh >/dev/null 2>&1; then
    gh release create "$TAG" "$APK" --repo "$UPDATE_REPO" --title "$TAG" --notes-file "$NOTES_FILE"
    echo "Gotowe: https://github.com/$UPDATE_REPO/releases/tag/$TAG"
else
    echo
    echo "Nie ma polecenia gh. Ręcznie, w przeglądarce:"
    echo "  1. https://github.com/$UPDATE_REPO/releases/new"
    echo "  2. Tag (\"Choose a tag\" -> utwórz nowy): $TAG"
    echo "  3. Tytuł: $TAG"
    echo "  4. Opis: wklej tekst wypisany wyżej (bez znaków '  | ')."
    echo "  4a. Załącz JEDEN plik, bez zmiany nazwy: $APK"
    echo "  5. NIE zaznaczaj \"Set as a pre-release\"; zostaw \"Set as the latest release\"."
    echo "  6. Publish release."
fi
