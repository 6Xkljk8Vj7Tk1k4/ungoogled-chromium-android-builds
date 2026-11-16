package org.chromium.chrome.browser.self_update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * Odbiera wynik instalacji zleconej przez Installer.
 *
 * Wynik przychodzi przez PendingIntent z Installer.resultSender: akcja jest
 * NASZA (ACTION_INSTALL_RESULT), a system dopisuje EXTRA_STATUS,
 * EXTRA_SESSION_ID, EXTRA_STATUS_MESSAGE i -- gdy trzeba potwierdzenia --
 * EXTRA_INTENT z oknem do pokazania. Po udanej aktualizacji samego siebie
 * wynik trafia już do procesu NOWEJ wersji.
 *
 * Zadeklarowany w AndroidManifest.xml jako android:exported="false".
 */
public final class SelfUpdateResultReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_RESULT =
            "org.chromium.chrome.browser.self_update.INSTALL_RESULT";

    @Override
    @SuppressWarnings("deprecation") // typowany getParcelableExtra wymaga API 33, a minSdk to 29
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) return;
            // Brak statusu traktujemy jak błąd, nie jak sukces.
            int status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            SelfUpdater.onInstallResult(
                    context.getApplicationContext(), sessionId, status, message, confirm);
        } catch (Throwable t) {
            // Updater nigdy nie może wywrócić przeglądarki.
            Log.w(SelfUpdater.TAG, "wynik instalacji", t);
        }
    }
}
