package org.chromium.chrome.browser.self_update;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wbudowany updater: sprawdza wydania w repozytorium na GitHubie, pobiera
 * nowszy APK i instaluje go przez PackageInstaller.
 *
 * Wołany z ChromeActivitySessionTracker przy wejściu przeglądarki na pierwszy
 * plan i przy zejściu z niego. Cała praca (sieć, kopiowanie pliku) dzieje się
 * w jednym wątku roboczym; wątek główny tylko go uruchamia.
 *
 * Kod celowo nie importuje niczego z Chromium (kontekst aplikacji dostaje od
 * wołającego), żeby podbicia wersji Chromium nie mogły go zepsuć.
 *
 * Zasada nadrzędna: updater NIGDY nie może wywrócić przeglądarki. Każde
 * wejście (oba zaczepy, wątek roboczy, odbiornik wyniku) łapie Throwable.
 *
 * Przebieg jednego cyklu:
 *  1. jest pobrany i sprawdzony plik -> krok 3; inaczej, najwyżej raz na
 *     dobę, zapytanie o najnowsze wydanie;
 *  2. nowszy tag -> pobranie (tylko sieć bez limitu, ze wznawianiem),
 *     sprawdzenie rozmiaru, SHA-256 i tego, że to nasz pakiet;
 *  3. przygotowanie sesji instalatora i zlecenie instalacji:
 *     - Android 14+: system sam wybiera chwilę, gdy z przeglądarki nikt nie
 *       korzysta (instalacja ubija proces);
 *     - starsze: przy zejściu przeglądarki z ekranu;
 *     - gdy system zażąda potwierdzenia: jego okno przy najbliższym
 *       otwarciu przeglądarki.
 *
 * Stan trzymamy w osobnym pliku SharedPreferences ("self_update"), nie
 * w preferencjach Chromium.
 */
public final class SelfUpdater {
    static final String TAG = "SelfUpdate";

    private static final String PREFS = "self_update";
    /** Najwcześniejszy czas następnego zapytania o wydanie. */
    private static final String K_NEXT_CHECK = "next_check_ms";
    /** Tag wydania, którego plik jest pobrany i sprawdzony. */
    private static final String K_READY_TAG = "ready_tag";
    private static final String K_SESSION_ID = "session_id";
    /** Kiedy zlecono instalację "na czas bezczynności" (0 = nie zlecono). */
    private static final String K_COMMIT_PENDING = "commit_pending_ms";
    /** Czas startu systemu w chwili zlecenia; po restarcie zlecenie przepada. */
    private static final String K_COMMIT_BOOT = "commit_pending_boot_ms";
    /** LEVEL_*: jak ostre warunki stawiamy systemowi przy wyborze chwili instalacji. */
    private static final String K_LEVEL = "constraint_level";
    /** System zażądał potwierdzenia dla bieżącej sesji. */
    private static final String K_CONFIRM = "confirm_mode";
    /** Kiedy użytkownik odrzucił okno systemu. */
    private static final String K_DECLINED = "declined_ms";
    /** Kiedy ostatnio pokazaliśmy ekran zgody na instalowanie. */
    private static final String K_GRANT_OFFERED = "grant_offered_ms";
    private static final String K_FAIL_TAG = "fail_tag";
    private static final String K_FAIL_COUNT = "fail_count";
    /** Tag porzucony po MAX_FAILURES nieudanych próbach. */
    private static final String K_SKIP_TAG = "skip_tag";
    /**
     * Ostatni tag zainstalowany przez updater. Chroni przed pętlą, gdy tag
     * wydania nie zgadza się z tagiem wpieczonym w opublikowany APK.
     */
    private static final String K_INSTALLED_TAG = "installed_tag";
    private static final String K_LAST_SEEN = "last_seen_tag";

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;
    /** Ponowienie, gdy wiemy o nowszej wersji, ale nie dało się jej teraz pobrać. */
    private static final long RETRY_MS = 15 * MINUTE_MS;
    private static final long CONSTRAINT_TIMEOUT_MS = 12 * HOUR_MS;
    private static final long MAX_APK_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_FAILURES = 3;

    /** Instalacja, gdy nikt nie korzysta z aplikacji ORAZ urządzenie leży bezczynnie. */
    private static final int LEVEL_IDLE = 0;
    /** Instalacja, gdy nikt nie korzysta z aplikacji. */
    private static final int LEVEL_NOT_INTERACTING = 1;
    /** Bez warunków: zwykłe zlecenie przy zejściu przeglądarki z ekranu. */
    private static final int LEVEL_NONE = 2;

    private static final AtomicBoolean sBusy = new AtomicBoolean();
    private static volatile boolean sForeground;
    private static volatile boolean sGrantOffered;
    /** Sesja, dla której w tym procesie już poprosiliśmy o okno potwierdzenia. */
    private static volatile int sConfirmSessionId = -1;
    /** Okno systemu, którego nie dało się pokazać, bo przeglądarka była w tle. */
    private static volatile Intent sPendingConfirm;
    private static boolean sAnnounced;

    private SelfUpdater() {}

    /**
     * Zaczep: przeglądarka weszła na pierwszy plan. Wątek główny.
     *
     * @param appContext kontekst aplikacji (nie aktywności -- trafia do wątku roboczego)
     */
    public static void onForegroundStart(Context appContext) {
        try {
            sForeground = true;
            if (!announceAndCheckEnabled()) return;
            Context ctx = appContext.getApplicationContext();
            SharedPreferences p = prefs(ctx);

            String seen = p.getString(K_LAST_SEEN, "");
            if (!UpdaterConfig.RELEASE_TAG.equals(seen)) {
                p.edit().putString(K_LAST_SEEN, UpdaterConfig.RELEASE_TAG).apply();
                // Cicha aktualizacja, której nikt nie zauważył, wygląda jak brak aktualizacji.
                if (!seen.isEmpty()) toast(ctx, "Chromium updated to " + UpdaterConfig.RELEASE_TAG);
            }

            Intent confirm = sPendingConfirm;
            if (confirm != null) {
                sPendingConfirm = null;
                launch(ctx, confirm);
                return;
            }
            startWorker(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "onForegroundStart", t);
        }
    }

    /** Zaczep: przeglądarka zeszła z pierwszego planu. Wątek główny. */
    public static void onForegroundEnd(Context appContext) {
        try {
            sForeground = false;
            if (!isEnabled()) return;
            Context ctx = appContext.getApplicationContext();
            SharedPreferences p = prefs(ctx);
            int sessionId = p.getInt(K_SESSION_ID, -1);
            // Tylko tryb bez warunków; pozostałe zlecają instalację wcześniej.
            if (sessionId == -1 || p.getBoolean(K_CONFIRM, false)
                    || constraintLevel(p) != LEVEL_NONE || sBusy.get()) {
                return;
            }
            if (!ctx.getPackageManager().canRequestPackageInstalls()) return;
            if (!Installer.commit(ctx, sessionId)) forgetSession(ctx, p, sessionId);
        } catch (Throwable t) {
            Log.w(TAG, "onForegroundEnd", t);
        }
    }

    /** Wynik instalacji z SelfUpdateResultReceiver. Wątek główny. */
    static void onInstallResult(
            Context ctx, int sessionId, int status, String message, Intent confirm) {
        SharedPreferences p = prefs(ctx);
        String tag = p.getString(K_READY_TAG, "");
        Log.i(TAG, "wynik: sesja " + sessionId + " status " + status + " tag '" + tag + "'"
                + (message == null ? "" : " (" + message + ")"));
        if (sessionId != p.getInt(K_SESSION_ID, -1)) {
            Log.i(TAG, "wynik: to nie jest biezaca sesja -- pomijam");
            return;
        }
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                p.edit()
                        .putString(K_INSTALLED_TAG, tag)
                        .remove(K_READY_TAG).remove(K_SESSION_ID)
                        .remove(K_COMMIT_PENDING).remove(K_COMMIT_BOOT)
                        .remove(K_CONFIRM).remove(K_DECLINED)
                        .remove(K_FAIL_TAG).remove(K_FAIL_COUNT)
                        .apply();
                cleanDir(dir(ctx), null);
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // System nie zgodził się na instalację bez pytania. Okno można
                // pokazać tylko z pierwszego planu.
                p.edit().putBoolean(K_CONFIRM, true).remove(K_COMMIT_PENDING).apply();
                if (confirm == null) break;
                if (sForeground) {
                    launch(ctx, confirm);
                } else {
                    sPendingConfirm = confirm;
                }
                break;
            case PackageInstaller.STATUS_FAILURE_TIMEOUT:
                // Warunki nie spełniły się w terminie: następnym razem łagodniejsze.
                p.edit()
                        .remove(K_COMMIT_PENDING)
                        .putInt(K_LEVEL, Math.min(LEVEL_NONE, constraintLevel(p) + 1))
                        .apply();
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                // Użytkownik zamknął okno systemu. To nie błąd; zapytamy jutro.
                p.edit()
                        .remove(K_SESSION_ID).remove(K_COMMIT_PENDING)
                        .putLong(K_DECLINED, System.currentTimeMillis())
                        .apply();
                break;
            default:
                p.edit().remove(K_SESSION_ID).remove(K_COMMIT_PENDING).apply();
                countFailure(ctx, p, tag);
                break;
        }
    }

    // ------------------------------------------------------------------ cykl

    private static void startWorker(Context ctx) {
        if (!sBusy.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                cycle(ctx);
            } catch (Throwable t) {
                Log.w(TAG, "cykl", t);
            } finally {
                sBusy.set(false);
            }
        }, TAG);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void cycle(Context ctx) {
        SharedPreferences p = prefs(ctx);
        File dir = dir(ctx);
        // Użytkownik odrzucił okno systemu: przez dobę nie robimy nic, także
        // nie przygotowujemy nowej sesji (to kopiowanie całego APK).
        if (within(p.getLong(K_DECLINED, 0), System.currentTimeMillis(), DAY_MS)) return;

        String ready = p.getString(K_READY_TAG, "");
        if (!ready.isEmpty() && (!UpdateChecker.isValidTag(ready)
                || UpdateChecker.compareTags(ready, baseTag(p)) <= 0
                || !fileFor(dir, ready).isFile())) {
            // Plik zniknął (system czyści cache) albo już nie jest nowszy.
            p.edit().remove(K_READY_TAG).apply();
            cleanDir(dir, null);
            ready = "";
        }
        int sessionId = p.getInt(K_SESSION_ID, -1);
        if (sessionId != -1 && (ready.isEmpty() || !Installer.sessionExists(ctx, sessionId))) {
            forgetSession(ctx, p, sessionId);
            sessionId = -1;
        }
        Installer.abandonStale(ctx, sessionId);

        if (ready.isEmpty()) {
            ready = checkAndDownload(ctx, p, dir);
            if (ready.isEmpty()) return;
        }

        // Drugi obrót tylko wtedy, gdy system odrzucił zlecenie istniejącej sesji.
        for (int round = 0; round < 2; round++) {
            if (sessionId == -1) {
                sessionId = Installer.prepareSession(ctx, fileFor(dir, ready));
                if (sessionId == -1) {
                    countFailure(ctx, p, ready);
                    return;
                }
                p.edit().putInt(K_SESSION_ID, sessionId)
                        .remove(K_COMMIT_PENDING).remove(K_CONFIRM).apply();
            }
            if (requestInstall(ctx, p, sessionId)) return;
            forgetSession(ctx, p, sessionId);
            sessionId = -1;
        }
    }

    /** @return tag gotowego pliku albo pusty napis */
    private static String checkAndDownload(Context ctx, SharedPreferences p, File dir) {
        long now = System.currentTimeMillis();
        // `adb shell setprop log.tag.SelfUpdate VERBOSE` wymusza sprawdzenie (testy).
        boolean force = Log.isLoggable(TAG, Log.VERBOSE);
        long next = p.getLong(K_NEXT_CHECK, 0);
        // Drugi warunek: zegar cofnięty o więcej niż dobę nie może zablokować sprawdzania.
        if (!force && now < next && next - now <= DAY_MS) return "";

        UpdateChecker.ReleaseInfo info = UpdateChecker.fetchLatest(apiUrl());
        if (info == null) {
            setNextCheck(p, now + HOUR_MS);
            return "";
        }
        String base = baseTag(p);
        if (UpdateChecker.compareTags(info.tag, base) <= 0) {
            Log.i(TAG, "aktualne: wydanie " + info.tag + ", u nas " + base);
            setNextCheck(p, now + DAY_MS);
            cleanDir(dir, null);
            return "";
        }
        Log.i(TAG, "nowsze wydanie: " + info.tag + " (u nas " + base + ")");
        if (!force && info.tag.equals(p.getString(K_SKIP_TAG, ""))) {
            Log.i(TAG, "wydanie " + info.tag + " porzucone po " + MAX_FAILURES + " probach");
            setNextCheck(p, now + DAY_MS);
            return "";
        }
        if (info.apkUrl.isEmpty() || info.apkSize <= 0 || info.apkSize > MAX_APK_BYTES
                || !isAllowedUrl(info.apkUrl)) {
            Log.i(TAG, "wydanie " + info.tag + " bez uzywalnego pliku APK");
            setNextCheck(p, now + DAY_MS);
            return "";
        }
        cleanDir(dir, info.tag);
        if (!isUnmetered(ctx)) {
            Log.i(TAG, "siec z limitem -- pobieranie odlozone");
            setNextCheck(p, now + RETRY_MS);
            return "";
        }
        // Pobrany plik + jego kopia w sesji instalatora + zapas na samą instalację.
        if (dir.getUsableSpace() < 3 * info.apkSize) {
            Log.i(TAG, "za malo miejsca na " + info.apkSize + " B");
            setNextCheck(p, now + DAY_MS);
            return "";
        }

        File target = fileFor(dir, info.tag);
        int result = ApkDownloader.fetch(target, info.apkUrl, info.apkSize, info.apkSha256);
        if (result == ApkDownloader.TRANSIENT) {
            setNextCheck(p, now + RETRY_MS);
            return "";
        }
        if (result != ApkDownloader.OK || !isOurPackage(ctx, target)) {
            target.delete();
            countFailure(ctx, p, info.tag);
            setNextCheck(p, now + HOUR_MS);
            return "";
        }
        Log.i(TAG, "plik wydania " + info.tag + " pobrany i sprawdzony");
        p.edit().putString(K_READY_TAG, info.tag).apply();
        setNextCheck(p, now + DAY_MS);
        return info.tag;
    }

    /** @return false, gdy system odrzucił zlecenie i sesję trzeba przygotować od nowa */
    private static boolean requestInstall(Context ctx, SharedPreferences p, int sessionId) {
        long now = System.currentTimeMillis();
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            offerGrant(ctx, p, now);
            return true;
        }
        if (p.getBoolean(K_CONFIRM, false)) {
            // Zlecenie z pierwszego planu: odbiornik wyniku od razu pokaże okno systemu.
            if (!sForeground || sConfirmSessionId == sessionId) return true;
            sConfirmSessionId = sessionId;
            return Installer.commit(ctx, sessionId);
        }
        int level = constraintLevel(p);
        if (level != LEVEL_NONE) {
            long pending = p.getLong(K_COMMIT_PENDING, 0);
            if (pending != 0 && within(pending, now, CONSTRAINT_TIMEOUT_MS + RETRY_MS)
                    && Math.abs(bootTime() - p.getLong(K_COMMIT_BOOT, 0)) < 5 * MINUTE_MS) {
                return true; // system wciąż czeka na dobrą chwilę
            }
            if (Installer.commitWhenIdle(
                    ctx, sessionId, CONSTRAINT_TIMEOUT_MS, level == LEVEL_IDLE)) {
                p.edit().putLong(K_COMMIT_PENDING, now).putLong(K_COMMIT_BOOT, bootTime())
                        .apply();
                return true;
            }
            // System nie przyjmuje zleceń z warunkami -- zostaje zwykłe zlecenie.
            p.edit().putInt(K_LEVEL, LEVEL_NONE).apply();
        }
        // Tryb bez warunków: z pierwszego planu zleci to onForegroundEnd.
        return sForeground || Installer.commit(ctx, sessionId);
    }

    // --------------------------------------------------------------- pomocnicze

    private static boolean isEnabled() {
        return !UpdaterConfig.UPDATE_REPO.isEmpty()
                && UpdateChecker.isValidTag(UpdaterConfig.RELEASE_TAG);
    }

    private static boolean announceAndCheckEnabled() {
        boolean enabled = isEnabled();
        if (!sAnnounced) {
            sAnnounced = true;
            // Te trzy linie to zarazem znaczniki, po których release_android.sh
            // odczytuje parametry z gotowego APK -- muszą zostać wywołaniami Log.i
            // z samą stałą (inaczej R8 usunie nieużywane literały).
            Log.i(TAG, UpdaterConfig.RELEASE_TAG_MARKER);
            Log.i(TAG, UpdaterConfig.REPO_MARKER);
            Log.i(TAG, UpdaterConfig.TEST_API_MARKER);
            if (!enabled) Log.i(TAG, "updater wylaczony");
        }
        return enabled;
    }

    private static String apiUrl() {
        if (!UpdaterConfig.UPDATE_API_URL.isEmpty()) return UpdaterConfig.UPDATE_API_URL;
        return "https://api.github.com/repos/" + UpdaterConfig.UPDATE_REPO + "/releases/latest";
    }

    /** Poza buildem testowym plik może pochodzić tylko z wydań naszego repozytorium. */
    private static boolean isAllowedUrl(String url) {
        if (!UpdaterConfig.UPDATE_API_URL.isEmpty()) return true;
        return url.startsWith(
                "https://github.com/" + UpdaterConfig.UPDATE_REPO + "/releases/download/");
    }

    /** Wersja, od której wydanie musi być nowsze: większy z tagu builda i ostatnio zainstalowanego. */
    private static String baseTag(SharedPreferences p) {
        String installed = p.getString(K_INSTALLED_TAG, "");
        if (UpdateChecker.isValidTag(installed)
                && UpdateChecker.compareTags(installed, UpdaterConfig.RELEASE_TAG) > 0) {
            return installed;
        }
        return UpdaterConfig.RELEASE_TAG;
    }

    private static int constraintLevel(SharedPreferences p) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return LEVEL_NONE;
        return p.getInt(K_LEVEL, LEVEL_IDLE);
    }

    private static void countFailure(Context ctx, SharedPreferences p, String tag) {
        int count = tag.equals(p.getString(K_FAIL_TAG, "")) ? p.getInt(K_FAIL_COUNT, 0) + 1 : 1;
        Log.i(TAG, "nieudana proba " + count + "/" + MAX_FAILURES + " dla " + tag);
        SharedPreferences.Editor e = p.edit().putString(K_FAIL_TAG, tag).putInt(K_FAIL_COUNT, count);
        if (count >= MAX_FAILURES) {
            e.putString(K_SKIP_TAG, tag).remove(K_READY_TAG);
            cleanDir(dir(ctx), null);
        }
        e.apply();
    }

    private static void forgetSession(Context ctx, SharedPreferences p, int sessionId) {
        Installer.abandon(ctx, sessionId);
        p.edit().remove(K_SESSION_ID).remove(K_COMMIT_PENDING).remove(K_CONFIRM).apply();
    }

    /** Jednorazowa zgoda "instalowanie nieznanych aplikacji" dla przeglądarki. */
    private static void offerGrant(Context ctx, SharedPreferences p, long now) {
        if (!sForeground || sGrantOffered || within(p.getLong(K_GRANT_OFFERED, 0), now, DAY_MS)) {
            Log.i(TAG, "brak zgody na instalowanie aplikacji -- aktualizacja czeka");
            return;
        }
        sGrantOffered = true;
        p.edit().putLong(K_GRANT_OFFERED, now).apply();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, "Chromium update is ready. Allow installs from this source.",
                        Toast.LENGTH_LONG).show();
                launch(ctx, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName())));
            } catch (Throwable t) {
                Log.w(TAG, "ekran zgody", t);
            }
        });
    }

    private static void launch(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.i(TAG, "nie da sie pokazac okna: " + e);
        }
    }

    private static void toast(Context ctx, String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Log.w(TAG, "toast", t);
            }
        });
    }

    /** Ten sam pakiet i wersja nie niższa niż zainstalowana (podpis sprawdzi sam Android). */
    private static boolean isOurPackage(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), 0);
            if (archive == null || !ctx.getPackageName().equals(archive.packageName)) {
                Log.i(TAG, "plik nie jest pakietem " + ctx.getPackageName());
                return false;
            }
            long installed = pm.getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
            if (archive.getLongVersionCode() < installed) {
                Log.i(TAG, "plik ma nizszy versionCode niz zainstalowany");
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.i(TAG, "sprawdzanie pakietu: " + e);
            return false;
        }
    }

    private static boolean isUnmetered(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File dir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "self_update");
        dir.mkdirs();
        return dir;
    }

    private static File fileFor(File dir, String tag) {
        return new File(dir, tag + ".apk");
    }

    /** Kasuje pliki updatera poza plikami wskazanego tagu (null = wszystkie). */
    private static void cleanDir(File dir, String keepTag) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (keepTag == null || !f.getName().startsWith(keepTag + ".apk")) f.delete();
        }
    }

    private static void setNextCheck(SharedPreferences p, long when) {
        p.edit().putLong(K_NEXT_CHECK, when).apply();
    }

    /** Czy `then` leży w przeszłości nie dalej niż `span` (zegar cofnięty = nie). */
    private static boolean within(long then, long now, long span) {
        return then != 0 && now >= then && now - then < span;
    }

    /** Przybliżony czas startu systemu; zmienia się po restarcie. */
    private static long bootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }
}
