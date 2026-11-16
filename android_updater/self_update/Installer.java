package org.chromium.chrome.browser.self_update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Cienka warstwa nad PackageInstaller: przygotowanie sesji z pobranego APK
 * i zlecenie instalacji. O tym, KIEDY zlecać, decyduje SelfUpdater.
 *
 * Android przyjmie aktualizację tylko wtedy, gdy APK jest podpisany tym samym
 * kluczem co zainstalowana wersja -- to jedyna realna ochrona przed podmianą.
 */
final class Installer {
    private static final String TAG = SelfUpdater.TAG;

    private Installer() {}

    private static PackageInstaller installer(Context ctx) {
        return ctx.getPackageManager().getPackageInstaller();
    }

    static boolean sessionExists(Context ctx, int sessionId) {
        try {
            return installer(ctx).getSessionInfo(sessionId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void abandon(Context ctx, int sessionId) {
        try {
            installer(ctx).abandonSession(sessionId);
        } catch (RuntimeException e) {
            // Sesja już nie istnieje albo nie jest nasza -- nie ma czego sprzątać.
        }
    }

    /**
     * Porzuca nasze sesje inne niż wskazana. Sesja, której zapis przerwało
     * ubicie procesu, nie ma identyfikatora w naszym stanie i bez tego
     * trzymałaby kopię APK do czasu, aż system sam ją usunie.
     */
    static void abandonStale(Context ctx, int keepSessionId) {
        try {
            for (PackageInstaller.SessionInfo info : installer(ctx).getMySessions()) {
                if (info.getSessionId() != keepSessionId) abandon(ctx, info.getSessionId());
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "sesje: " + e);
        }
    }

    /**
     * Tworzy sesję i kopiuje do niej plik. Blokuje wątek na czas kopiowania.
     *
     * @return identyfikator sesji albo -1
     */
    static int prepareSession(Context ctx, File apk) {
        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ctx.getPackageName());
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Prośba, nie gwarancja: system i tak może zażądać potwierdzenia
                // (STATUS_PENDING_USER_ACTION) -- patrz SelfUpdater.
                params.setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            sessionId = installer(ctx).createSession(params);
            try (PackageInstaller.Session session = installer(ctx).openSession(sessionId);
                    InputStream in = new FileInputStream(apk);
                    OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            Log.i(TAG, "sesja " + sessionId + " przygotowana (" + apk.length() + " B)");
            return sessionId;
        } catch (Exception e) {
            Log.i(TAG, "sesja: " + e);
            if (sessionId != -1) abandon(ctx, sessionId);
            return -1;
        }
    }

    /** Zleca instalację od razu. @return false, gdy system odrzucił zlecenie */
    static boolean commit(Context ctx, int sessionId) {
        try (PackageInstaller.Session session = installer(ctx).openSession(sessionId)) {
            session.commit(resultSender(ctx, sessionId));
            Log.i(TAG, "sesja " + sessionId + ": zlecona instalacja");
            return true;
        } catch (Exception e) {
            Log.i(TAG, "sesja " + sessionId + ": zlecenie odrzucone: " + e);
            return false;
        }
    }

    /**
     * Zleca instalację w chwili, którą wybierze system (Android 14+): gdy
     * z aplikacji nikt nie korzysta i -- opcjonalnie -- urządzenie leży
     * bezczynnie. Instalacja ubija proces, więc robienie tego w trakcie pracy
     * kasowałoby np. wpisywany formularz.
     *
     * @return false, gdy system odrzucił zlecenie (wtedy wołający przechodzi
     *     na zwykły commit przy zejściu z ekranu)
     */
    static boolean commitWhenIdle(
            Context ctx, int sessionId, long timeoutMs, boolean requireDeviceIdle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false;
        try {
            Api34.commitWhenIdle(installer(ctx), sessionId, resultSender(ctx, sessionId),
                    timeoutMs, requireDeviceIdle);
            Log.i(TAG, "sesja " + sessionId + ": instalacja zlecona na czas bezczynnosci"
                    + (requireDeviceIdle ? " urzadzenia" : " aplikacji"));
            return true;
        } catch (Exception e) {
            Log.i(TAG, "sesja " + sessionId + ": zlecenie z warunkami odrzucone: " + e);
            return false;
        }
    }

    /** Osobna klasa, żeby starsze systemy nie musiały weryfikować odwołań do API 34. */
    private static final class Api34 {
        static void commitWhenIdle(PackageInstaller installer, int sessionId,
                IntentSender sender, long timeoutMs, boolean requireDeviceIdle) {
            PackageInstaller.InstallConstraints.Builder builder =
                    new PackageInstaller.InstallConstraints.Builder()
                            .setAppNotInteractingRequired();
            if (requireDeviceIdle) builder.setDeviceIdleRequired();
            installer.commitSessionAfterInstallConstraintsAreMet(
                    sessionId, sender, builder.build(), timeoutMs);
        }
    }

    /**
     * System dopisuje wynik do tej intencji, więc PendingIntent musi być
     * zmienny, a zmienny PendingIntent musi wskazywać komponent jawnie
     * (wymóg od Androida 14).
     */
    private static IntentSender resultSender(Context ctx, int sessionId) {
        Intent intent = new Intent(ctx, SelfUpdateResultReceiver.class)
                .setAction(SelfUpdateResultReceiver.ACTION_INSTALL_RESULT);
        return PendingIntent.getBroadcast(ctx, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE)
                .getIntentSender();
    }
}
