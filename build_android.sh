#!/bin/bash
set -euo pipefail

usage() {
    echo "Użycie: $0 [--variant ungoogled|vanilla] [--version TAG] [--builder-only] [-j LICZBA_WATKOW_NINJA]"
    echo "  Buduje wariant Android (arm64)."
    echo
    echo "  --variant V     ungoogled (domyślny) -- 39 flag GN + pełna seria patchy"
    echo "                  ungoogled-chromium + literały + domain_substitution."
    echo "                  vanilla -- czyste Chromium, bez żadnych patchy/literałów/"
    echo "                  domain_substitution (test izolujący \"czy sam pipeline i"
    echo "                  ta wersja Chromium są zdrowe\")."
    echo "  --version TAG   Tag wydania ungoogled-chromium, np. 151.0.7922.71-1."
    echo "                  Domyślnie najnowsze wydanie z GitHuba (= Chrome Stable)."
    echo "                  Wyznacza naraz wersję Chromium i zestaw patchy."
    echo "  --builder-only  Buduje tylko stage 'builder' (fetch + gclient sync +"
    echo "                  patche/literały/domain_substitution), bez GN gen/ninja/"
    echo "                  .apk — szybsza walidacja wczesnych kroków bez"
    echo "                  wielogodzinnej kompilacji."
    echo "  -j N            Nadpisuje NINJA_JOBS (domyślnie 8 — Android jest bardziej"
    echo "                  pamięciożerny niż desktop, patrz Containerfile.android)."
    echo "  --release N     Nasz numer przebudowy tej samej wersji Chromium (domyślnie"
    echo "                  1). Tag wydania dla wbudowanego updatera = <wersja>-N;"
    echo "                  to NIE jest -N z tagu ungoogled-chromium."
    echo "  --update-repo R Repozytorium na GitHubie (właściciel/nazwa), w którego"
    echo "                  wydaniach APK szuka aktualizacji. Domyślnie z"
    echo "                  android_update.conf; pusty napis wyłącza updater."
    echo "  --update-api-url URL"
    echo "                  TYLKO do testów: adres zamiast API GitHuba (serwer"
    echo "                  android_updater/test_server.py). Takiego APK nie da się"
    echo "                  opublikować -- release_android.sh go odrzuci."
}

TARGET="export"
NINJA_JOBS=""
VARIANT="ungoogled"
UC_VERSION=""
RELEASE_N=1
UPDATE_REPO=""
UPDATE_REPO_SET=0
UPDATE_API_URL=""

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --variant) VARIANT="$2"; shift 2 ;;
        --version) UC_VERSION="$2"; shift 2 ;;
        --builder-only) TARGET="builder"; shift ;;
        -j) NINJA_JOBS="$2"; shift 2 ;;
        --release) RELEASE_N="$2"; shift 2 ;;
        --update-repo) UPDATE_REPO="$2"; UPDATE_REPO_SET=1; shift 2 ;;
        --update-api-url) UPDATE_API_URL="$2"; shift 2 ;;
        *) echo "BŁĄD: nieznana opcja '$1'"; usage; exit 1 ;;
    esac
done

case "$VARIANT" in
    ungoogled|vanilla) ;;
    *) echo "BŁĄD: nieznany wariant '${VARIANT}' (oczekiwano: ungoogled|vanilla)"; exit 1 ;;
esac
case "$RELEASE_N" in
    *[!0-9]*|'') echo "BŁĄD: --release oczekuje liczby całkowitej (dostarczone: '${RELEASE_N}')"; exit 1 ;;
esac

# Wersję ustalamy TUTAJ i podajemy jako build-arg (nie w samym kroku RUN):
# wtedy cache warstw unieważnia się dokładnie przy nowej wersji. Pułapka
# desktopowego kroku 3 (curl w RUN = stara wersja z cache) tu nie występuje.
if [ -z "${UC_VERSION}" ]; then
    UC_VERSION=$(curl -sf --max-time 30 https://api.github.com/repos/ungoogled-software/ungoogled-chromium/releases/latest \
        | grep -oP '"tag_name": "\K[^"]+' || true)
    if [ -z "${UC_VERSION}" ]; then
        echo "BŁĄD: nie udało się pobrać najnowszego tagu ungoogled-chromium z GitHub API (rate limit lub brak sieci)."
        echo "      Podaj wersję ręcznie: $0 --version <tag>"
        exit 1
    fi
fi
echo "ℹ️  ungoogled-chromium ${UC_VERSION} -> Chromium ${UC_VERSION%-*}"

# --- Wbudowany updater ----------------------------------------------------------
# Repozytorium: opcja --update-repo, a bez niej android_update.conf. Wariant
# vanilla nigdy nie dostaje updatera (żadnych naszych zmian w źródłach).
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$UPDATE_REPO_SET" -eq 0 ] && [ -f "$HERE/android_update.conf" ]; then
    UPDATE_REPO=$(sed -n 's/^UPDATE_REPO=//p' "$HERE/android_update.conf" | head -1 | tr -d '"'"'")
fi
if [ "$VARIANT" != "ungoogled" ]; then
    UPDATE_REPO=""
fi
if [ -n "$UPDATE_REPO" ] && ! [[ "$UPDATE_REPO" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]]; then
    echo "BŁĄD: repozytorium updatera '${UPDATE_REPO}' nie ma postaci właściciel/nazwa."
    exit 1
fi
if [ -n "$UPDATE_API_URL" ] && ! [[ "$UPDATE_API_URL" =~ ^https?://[A-Za-z0-9._:/?=\&%-]+$ ]]; then
    echo "BŁĄD: --update-api-url '${UPDATE_API_URL}' zawiera niedozwolone znaki."
    exit 1
fi
RELEASE_TAG="${UC_VERSION%-*}-${RELEASE_N}"

BUILD_ARGS=(--build-arg "VARIANT=${VARIANT}" --build-arg "UC_VERSION=${UC_VERSION}" \
    --build-arg "UPDATE_REPO=${UPDATE_REPO}" --build-arg "RELEASE_TAG=${RELEASE_TAG}" \
    --build-arg "UPDATE_API_URL=${UPDATE_API_URL}")
if [ -n "$NINJA_JOBS" ]; then
    BUILD_ARGS+=(--build-arg "NINJA_JOBS=${NINJA_JOBS}")
fi

# --- Własny klucz podpisu -------------------------------------------------------
# Bez klucza APK zostaje z publicznym kluczem debug Chromium. To wystarcza do
# własnego użytku bez updatera, ale updater bez własnego klucza byłby furtką:
# klucz debug leży w otwartych źródłach, więc każdy mógłby podpisać nim plik,
# który Android przyjmie jako aktualizację. Dlatego updater WYMAGA klucza.
#
# Klucz i hasło idą do builda jako sekrety (nie trafiają do warstw ani logu),
# a odcisk certyfikatu jako build-arg: unieważnia cache kroku podpisu i służy
# w nim do sprawdzenia, że gotowy APK ma właśnie ten podpis.
KEYDIR="${KEYSTORE_DIR:-$HOME/.keystores}"
KEYFILE="$KEYDIR/chromium-android-release.keystore"
PASSFILE="$KEYDIR/chromium-android-release.pass"
if [ -f "$KEYFILE" ]; then
    if ! SIGNING_CERT_SHA256=$("$HERE/keystore_android.sh" fingerprint); then
        echo "BŁĄD: nie da się odczytać odcisku klucza $KEYFILE."
        exit 1
    fi
    BUILD_ARGS+=(--secret "id=android_keystore,src=${KEYFILE}" \
                 --secret "id=android_keystore_pass,src=${PASSFILE}" \
                 --build-arg "SIGNING_CERT_SHA256=${SIGNING_CERT_SHA256}")
    echo "ℹ️  Podpis: własny klucz, odcisk certyfikatu ${SIGNING_CERT_SHA256}"
elif [ -n "$UPDATE_REPO" ]; then
    echo "BŁĄD: updater (repozytorium '${UPDATE_REPO}') wymaga własnego klucza podpisu,"
    echo "      a nie ma pliku $KEYFILE."
    echo "      Nowy klucz:        ./keystore_android.sh generate"
    echo "      Klucz z kopii:     ./keystore_android.sh restore"
    echo "      Build bez updatera: $0 --update-repo ''"
    exit 1
else
    echo "ℹ️  Podpis: publiczny klucz debug Chromium (brak własnego klucza)."
fi
if [ -n "$UPDATE_REPO" ]; then
    echo "ℹ️  Updater: repozytorium ${UPDATE_REPO}, tag wydania ${RELEASE_TAG}"
    if [ -n "$UPDATE_API_URL" ]; then
        echo "⚠️  BUILD TESTOWY: wydania z ${UPDATE_API_URL} zamiast z GitHuba"
    fi
else
    echo "ℹ️  Updater: wyłączony"
fi

echo -e "\n======================================"
echo "🛠️ BUDOWANIE WARIANTU ANDROID (arm64-v8a) — wariant: ${VARIANT}, target: ${TARGET}"
echo "======================================"
podman build -t "chromium-android-${VARIANT}" \
    -f Containerfile.android \
    --target "$TARGET" \
    "${BUILD_ARGS[@]}" .

if [ "$TARGET" = "builder" ]; then
    echo -e "\n✅ Stage 'builder' zbudowany (wariant ${VARIANT}: fetch + gclient sync"
    echo "   + patche/literały/domain_substitution)."
    echo "   Bez --builder-only ten skrypt zbuduje też GN gen/ninja/.apk i skopiuje"
    echo "   gotowy .apk do bieżącego katalogu."
    exit 0
fi

podman run --rm -v "$(pwd)":/host_output "chromium-android-${VARIANT}"

echo -e "\n✅ Zrobione! Gotowy .apk (${VARIANT}-chromium-*-arm64-v8a.apk) w bieżącym katalogu."
if [ -n "$UPDATE_REPO" ] && [ -z "$UPDATE_API_URL" ]; then
    echo "   Publikacja wydania ${RELEASE_TAG}: ./release_android.sh ${VARIANT}-chromium-${UC_VERSION%-*}-arm64-v8a.apk"
fi
