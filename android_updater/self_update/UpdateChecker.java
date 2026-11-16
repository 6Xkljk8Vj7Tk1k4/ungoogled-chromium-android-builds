package org.chromium.chrome.browser.self_update;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Pytanie o najnowsze wydanie (GitHub Releases API albo serwer testowy
 * w tym samym formacie) i porównywanie tagów.
 *
 * Metody sieciowe blokują wątek -- wołać wyłącznie poza wątkiem głównym.
 * Brak sieci, błąd HTTP czy śmieciowa odpowiedź to sytuacja zwyczajna:
 * wpis w logu i null, nigdy wyjątek.
 */
final class UpdateChecker {
    private static final String TAG = SelfUpdater.TAG;

    static final String USER_AGENT = "chromium-android-self-update";
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 30_000;

    /** Sufiks nazwy zasobu z naszym APK; w wydaniu musi być dokładnie jeden taki plik. */
    private static final String ASSET_SUFFIX = "-arm64-v8a.apk";
    /** Odpowiedź API to kilka-kilkanaście kB; limit chroni przed zalaniem pamięci. */
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private UpdateChecker() {}

    /** Opis wydania. Pola nigdy nie są null. */
    static final class ReleaseInfo {
        final String tag;
        /** Adres pobierania; pusty, gdy wydanie nie ma (dokładnie jednego) pliku APK. */
        final String apkUrl;
        /** Rozmiar w bajtach; -1, gdy nieznany. */
        final long apkSize;
        /** SHA-256 jako 64 małe znaki szesnastkowe; pusty, gdy serwer go nie podał. */
        final String apkSha256;

        ReleaseInfo(String tag, String apkUrl, long apkSize, String apkSha256) {
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
            this.apkSha256 = apkSha256;
        }
    }

    /** Zwraca opis najnowszego wydania albo null przy dowolnym problemie. */
    static ReleaseInfo fetchLatest(String apiUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // API GitHuba odrzuca zapytania bez User-Agent (403).
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/vnd.github+json");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.i(TAG, "sprawdzanie: HTTP " + code);
                return null;
            }
            JSONObject obj = new JSONObject(readLimited(conn.getInputStream()));
            String tag = obj.optString("tag_name", "");
            if (!isValidTag(tag)) {
                Log.i(TAG, "sprawdzanie: nieprawidlowy tag '" + tag + "'");
                return null;
            }

            String apkUrl = "";
            long apkSize = -1;
            String apkSha256 = "";
            int matches = 0;
            JSONArray assets = obj.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null || !asset.optString("name", "").endsWith(ASSET_SUFFIX)) continue;
                matches++;
                apkUrl = asset.optString("browser_download_url", "");
                apkSize = asset.optLong("size", -1);
                apkSha256 = parseSha256(asset.optString("digest", ""));
            }
            if (matches != 1) {
                Log.i(TAG, "sprawdzanie: wydanie " + tag + " ma " + matches + " plikow *"
                        + ASSET_SUFFIX + " (oczekiwano 1)");
                return new ReleaseInfo(tag, "", -1, "");
            }
            return new ReleaseInfo(tag, apkUrl, apkSize, apkSha256);
        } catch (Exception e) {
            Log.i(TAG, "sprawdzanie: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** "sha256:<64 hex>" -> same znaki szesnastkowe małymi literami; cokolwiek innego -> "". */
    static String parseSha256(String digest) {
        String prefix = "sha256:";
        if (digest == null || !digest.startsWith(prefix)) return "";
        String hex = digest.substring(prefix.length()).toLowerCase(java.util.Locale.ROOT);
        return hex.matches("[0-9a-f]{64}") ? hex : "";
    }

    /** Tag to człony liczbowe rozdzielone "." i "-", np. 157.0.8103.78-2. */
    static boolean isValidTag(String tag) {
        return tag != null && tag.length() <= 64 && tag.matches("\\d{1,9}([.-]\\d{1,9})*");
    }

    /**
     * Porównuje dwa PRAWIDŁOWE tagi człon po członie, liczbowo (brakujący człon = 0).
     *
     * @return wartość ujemna, gdy a jest starszy; 0, gdy równe; dodatnia, gdy a jest nowszy
     */
    static int compareTags(String a, String b) {
        String[] pa = a.split("[.-]");
        String[] pb = b.split("[.-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long xa = i < pa.length ? Long.parseLong(pa[i]) : 0;
            long xb = i < pb.length ? Long.parseLong(pb[i]) : 0;
            if (xa != xb) return xa < xb ? -1 : 1;
        }
        return 0;
    }

    private static String readLimited(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_RESPONSE_BYTES) throw new Exception("odpowiedz za duza");
            }
            return out.toString("UTF-8");
        }
    }
}
