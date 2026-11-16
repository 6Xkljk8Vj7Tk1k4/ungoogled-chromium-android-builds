package org.chromium.chrome.browser.self_update;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;

/**
 * Pobieranie APK ze wznawianiem i weryfikacją rozmiaru oraz SHA-256.
 *
 * Plik rośnie jako "<cel>.part"; po udanej weryfikacji jest przemianowywany
 * na docelowy. Przerwane pobieranie zostawia ".part" i następne wywołanie
 * dociąga resztę nagłówkiem Range (odpowiedź 206 = dalszy ciąg, 200 = serwer
 * zignorował Range, więc od zera).
 *
 * Blokuje wątek -- wołać wyłącznie poza wątkiem głównym.
 */
final class ApkDownloader {
    private static final String TAG = SelfUpdater.TAG;

    /** Plik pobrany i zweryfikowany. */
    static final int OK = 0;
    /** Problem przejściowy (sieć, serwer); ".part" zostaje, warto ponowić. */
    static final int TRANSIENT = 1;
    /** Dane nie zgadzają się z opisem wydania; ".part" skasowany. */
    static final int CORRUPT = 2;

    private static final int BUF = 64 * 1024;

    private ApkDownloader() {}

    /**
     * @param target       plik docelowy (katalog musi istnieć)
     * @param url          adres pobierania
     * @param expectedSize rozmiar z opisu wydania, większy od zera
     * @param expectedSha  SHA-256 (64 małe znaki szesnastkowe) albo pusty = nie sprawdzać
     */
    static int fetch(File target, String url, long expectedSize, String expectedSha) {
        File part = new File(target.getPath() + ".part");
        target.delete();
        long have = part.isFile() ? part.length() : 0;
        if (have > expectedSize) {
            part.delete();
            have = 0;
        }
        if (have < expectedSize) {
            int result = transfer(part, url, have, expectedSize);
            if (result != OK) return result;
        }
        if (part.length() != expectedSize) {
            // Serwer zamknął połączenie przed końcem -- reszta przy następnej próbie.
            Log.i(TAG, "pobieranie: jest " + part.length() + " z " + expectedSize + " B");
            return TRANSIENT;
        }
        if (!expectedSha.isEmpty()) {
            String actual = sha256Hex(part);
            if (!expectedSha.equals(actual)) {
                // Wznowienie z uszkodzonego pliku dałoby znowu złą sumę.
                part.delete();
                Log.i(TAG, "pobieranie: zla suma SHA-256 (jest " + actual + ")");
                return CORRUPT;
            }
        } else {
            Log.i(TAG, "pobieranie: wydanie bez sumy SHA-256, sprawdzony tylko rozmiar");
        }
        if (!part.renameTo(target)) {
            Log.i(TAG, "pobieranie: nie da sie przemianowac pliku .part");
            return TRANSIENT;
        }
        return OK;
    }

    private static int transfer(File part, String url, long have, long expectedSize) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(UpdateChecker.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(UpdateChecker.READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", UpdateChecker.USER_AGENT);
            // Bez przezroczystej kompresji: zakresy bajtów muszą dotyczyć surowego pliku.
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");

            int code = conn.getResponseCode();
            boolean append;
            if (code == 206 && have > 0) {
                String range = conn.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + have + "-")) {
                    Log.i(TAG, "pobieranie: nieoczekiwany Content-Range '" + range + "'");
                    part.delete();
                    return TRANSIENT;
                }
                append = true;
            } else if (code == 200) {
                append = false;
                have = 0;
            } else {
                Log.i(TAG, "pobieranie: HTTP " + code);
                // 416 = nasz fragment nie pasuje do pliku na serwerze.
                if (code == 416) part.delete();
                return TRANSIENT;
            }

            Log.i(TAG, "pobieranie: start od " + have + " z " + expectedSize + " B");
            long total = have;
            long nextReport = total + expectedSize / 10;
            try (InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[BUF];
                int n;
                while (total <= expectedSize && (n = in.read(buf)) != -1) {
                    total += n;
                    if (total > expectedSize) break;
                    out.write(buf, 0, n);
                    if (total >= nextReport) {
                        Log.i(TAG, "pobieranie: " + (total * 100 / expectedSize) + "%");
                        nextReport += expectedSize / 10;
                    }
                }
            }
            if (total > expectedSize) {
                Log.i(TAG, "pobieranie: serwer wysyla wiecej niz " + expectedSize + " B");
                part.delete();
                return CORRUPT;
            }
            return OK;
        } catch (Exception e) {
            Log.i(TAG, "pobieranie: " + e);
            return TRANSIENT;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** SHA-256 pliku jako małe znaki szesnastkowe; pusty napis, gdy nie da się policzyć. */
    static String sha256Hex(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
