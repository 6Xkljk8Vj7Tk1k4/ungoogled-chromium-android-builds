#!/bin/bash
# Instaluje zbudowany .apk na podłączonym urządzeniu i wyłącza raportowanie
# metryk w profilu.
#
# UWAGA (stan po sesji "pełna seria patchy ungoogled", Containerfile.android,
# sekcje 5d/5e/5f): ten skrypt NIE JEST już potrzebny do
# ustawienia prywatności -- prawie wszystko, co kiedyś ustawiał tu przez
# adb/CDP PO instalacji, jest teraz wpieczone w .apk PRZED kompilacją i
# działa od pierwszego uruchomienia, bez adb, bez komputera:
#   - Safe Browsing, prefetch sieciowy, wykrywanie wycieków haseł,
#     Do Not Track -- 4 literały wpisane wprost w kod (sekcja "5e" w
#     Containerfile.android)
#   - Logowanie do konta (kSigninAllowed) -- 5. literał, wpisany przy okazji
#     naprawy patcha `remove-unused-preferences-fields.patch` (sekcja "5d")
#   - Podpowiedzi wyszukiwania, strony błędów Google, autofill (karty i
#     adresy), dostęp do form płatności, menedżer haseł Google, tryb DNS --
#     pokryte przez PEŁNĄ serię 106/108 patchy ungoogled-chromium (sekcja
#     "5d"): `extra/inox-patchset/0006-modify-default-prefs.patch` i
#     `core/ungoogled-chromium/doh-changes.patch`
#
# JEDYNE ustawienie, którego to NIE dotyczy: raportowanie metryk
# (user_experience_metrics.reporting_enabled, "Pomóż w ulepszaniu funkcji i
# działania Chrome"). Jego domyślna wartość na Androidzie/POSIX NIE jest
# literałem ani czymś, co patch mógłby bezpiecznie nadpisać -- pochodzi z
# GoogleUpdateSettings::GetCollectStatsConsent() ->
# ClientIdBackupFileManager::ClientIdFromCacheOrDisk(), czyli z pliku na
# dysku współdzielonego z infrastrukturą zgody Crashpad
# (chrome/installer/util/google_update_settings_posix.cc). Patchowanie tego
# niesie ryzyko efektów ubocznych w raportowaniu awarii gdzie indziej, więc
# świadomie zostaje jako zwykły przełącznik w Ustawieniach -- ten skrypt
# istnieje wyłącznie po to, żeby wyłączyć go automatycznie, bez ręcznego
# dotykania UI po każdej instalacji.
#
# Dlaczego to w ogóle działa: ten profil NIE ma pliku "Secure Preferences",
# więc mechanizm chronionych preferencji (HMAC) nie odrzuca zmian z zewnątrz.
# Zweryfikowane empirycznie: wartość przeżywa pełny cykl uruchom/zamknij,
# czyli Chromium jej nie resetuje przy własnym zapisie. Gdyby w przyszłej
# wersji pojawił się "Secure Preferences", ten skrypt przestanie działać po
# cichu -- dlatego na końcu WERYFIKUJE zapis i głośno failuje.
#
# Ustawienie siedzi w "Local State" (poziom przeglądarki, nie profilu) --
# zweryfikowane empirycznie przez ręczne wyłączenie w UI i porównanie z
# profilowym "Preferences", nie zgadnięte.
#
# APK jest OPCJONALNY: bez niego skrypt zakłada, że $PKG jest już
# zainstalowany (np. dotknięciem .apk w menedżerze plików na tablecie) i
# tylko dokłada wyłączenie metryk -- bo to ono wymaga roota przez adb, nie
# samo pobranie pliku. Instalacja przez tapnięcie w plik na tablecie daje
# od razu Safe Browsing/Do Not Track/autofill/itd. wyłączone (wpieczone w
# binarkę), ale metryki zostają "włączone", dopóki nie odpalisz tego skryptu
# albo nie wyłączysz ich ręcznie w Ustawieniach.
set -euo pipefail

PKG="${PKG:-org.chromium.chrome.stable}"
APK="${1:-}"

usage() {
    echo "Użycie: $0 [plik.apk]"
    echo "  Z argumentem   -- instaluje/aktualizuje .apk, potem wyłącza raportowanie metryk."
    echo "  Bez argumentu  -- zakłada, że \$PKG jest już zainstalowany (np. ręcznie),"
    echo "                    tylko wyłącza raportowanie metryk na istniejącej instalacji."
    echo "  PKG=<nazwa.pakietu> $0 [plik.apk]   # domyślnie org.chromium.chrome.stable"
    echo
    echo "Reszta ustawień prywatności (Safe Browsing, Do Not Track, prefetch,"
    echo "wycieki haseł, logowanie do konta, autofill, DNS, ...) jest już wpieczona"
    echo "w .apk od pierwszego uruchomienia -- ten skrypt NIE musi ich ustawiać."
    echo
    echo "Wymaga włączonego debugowania z prawami roota"
    echo "(Ustawienia -> System -> Opcje programisty), bo build jest release'owy"
    echo "i run-as nie da dostępu do katalogu profilu."
}

if [ -n "$APK" ]; then
    [ -f "$APK" ] || { echo "BŁĄD: nie ma pliku $APK"; exit 1; }
fi

command -v adb >/dev/null || { echo "BŁĄD: brak adb"; exit 1; }
[ -n "$(adb devices | sed -n '2p')" ] || { echo "BŁĄD: nie widzę urządzenia (adb devices)"; exit 1; }

echo "==> Podnoszę adb do roota..."
adb root >/dev/null 2>&1 || true
sleep 2
adb wait-for-device
if [ "$(adb shell id -u | tr -d '\r')" != "0" ]; then
    echo "BŁĄD: adb nie ma roota. Włącz debugowanie z prawami roota w Opcjach programisty."
    exit 1
fi

if [ -n "$APK" ]; then
    echo "==> Instaluję $APK ..."
    adb install -r "$APK"
else
    echo "==> Bez pliku .apk -- zakładam, że $PKG jest już zainstalowany..."
    adb shell pm path "$PKG" >/dev/null 2>&1 || {
        echo "BŁĄD: $PKG nie jest zainstalowany. Podaj plik .apk albo zainstaluj go najpierw."
        exit 1
    }
fi

echo "==> Pierwsze uruchomienie (tworzy katalog profilu / Local State)..."
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
adb shell am force-stop "$PKG"
sleep 2

LOCAL_STATE="/data/data/${PKG}/app_chrome/Local State"
adb shell "[ -f '$LOCAL_STATE' ]" || { echo "BŁĄD: brak '$LOCAL_STATE' -- profil się nie utworzył"; exit 1; }

# UID aplikacji ZMIENIA SIĘ przy każdej reinstalacji -- odczytujemy go, zamiast
# zaszywać na sztywno, inaczej po chownie plik stanie się nieczytelny dla apki.
OWNER=$(adb shell "stat -c '%u:%g' '$LOCAL_STATE'" | tr -d '\r')
echo "==> Właściciel Local State: $OWNER"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
adb pull "$LOCAL_STATE" "$TMP/local_state.json" >/dev/null

python3 - "$TMP/local_state.json" <<'PYEOF'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d.setdefault('user_experience_metrics', {})['reporting_enabled'] = False
json.dump(d, open(p, 'w'), separators=(',', ':'))
print("==> Wyłączone raportowanie metryk (Local State)")
PYEOF

# "$LOCAL_STATE" zawiera spację ("Local State") -- adb shell konkatenuje
# argumenty w jeden string przed wysłaniem do zdalnej powłoki, więc bez
# osadzonych cudzysłowów rozpadnie się na dwa osobne argumenty (zaobserwowane
# realnie: chown dostał "Local" i "State" jako dwa pliki, oba nieistniejące).
adb push "$TMP/local_state.json" "$LOCAL_STATE" >/dev/null
adb shell "chown '$OWNER' '$LOCAL_STATE'"
adb shell "chmod 600 '$LOCAL_STATE'"

echo "==> Weryfikacja (uruchamiam i zamykam, sprawdzam czy przetrwało)..."
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
adb shell am force-stop "$PKG"
sleep 2
adb pull "$LOCAL_STATE" "$TMP/after_local_state.json" >/dev/null

python3 - "$TMP/after_local_state.json" <<'PYEOF'
import json, sys
ls = json.load(open(sys.argv[1]))
value = ls.get('user_experience_metrics', {}).get('reporting_enabled')
if value != False:
    print(f"    FAIL user_experience_metrics.reporting_enabled = {value}")
    print("\nBŁĄD: Chromium zresetowało to ustawienie.")
    print("Prawdopodobnie pojawił się mechanizm chronionych preferencji")
    print("(plik 'Secure Preferences') -- ten skrypt wymaga wtedy przerobienia.")
    sys.exit(1)
print(f"    OK   user_experience_metrics.reporting_enabled = {value}")
PYEOF

echo
echo "✅ Gotowe. $PKG zainstalowany, raportowanie metryk wyłączone."
echo "   (reszta ustawień prywatności jest już wpieczona w .apk od buildu)"
