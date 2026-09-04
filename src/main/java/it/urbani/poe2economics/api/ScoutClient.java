package it.urbani.poe2economics.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Client per l'API pubblica di poe2scout (api.poe2scout.com).
 *
 * Trasporto: HttpURLConnection, non java.net.http.HttpClient. Il crawler e' sequenziale
 * e rate-limited, quindi il non-bloccante non serve a niente, e HttpURLConnection non
 * apre selector NIO (che in ambienti sandboxati falliscono con "loopback connection").
 *
 * Note verificate sul campo:
 *  - il nome lega va passato per esteso e url-encoded ("Dawn%20of%20the%20Hunt");
 *    lo ShortName ("hunt") restituisce 400
 *  - dataPoints alti su ByCategory danno 400: lo storico si prende da DailyStatsHistory
 *  - referenceCurrency NON converte CurrentPrice: tutti i prezzi restano in Exalted
 */
public final class ScoutClient {

    private static final String BASE = "https://api.poe2scout.com";
    private static final String REALM = "poe2";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_ATTEMPTS = 5;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;
    private final long minIntervalMs;

    private long lastCallAt = 0L;
    private long requestCount = 0L;

    public ScoutClient(String contact, long minIntervalMs) {
        this.userAgent = "poe2economics/0.1 (contact: " + contact + ")";
        this.minIntervalMs = minIntervalMs;
    }

    public long requestCount() {
        return requestCount;
    }

    // --- endpoint ---------------------------------------------------------

    public JsonNode leagues() {
        return get("/" + REALM + "/Leagues");
    }

    public JsonNode categories(String league) {
        return get("/" + REALM + "/Leagues/" + enc(league) + "/Items/Categories");
    }

    public JsonNode currenciesByCategory(String league, String category, int page, int perPage) {
        return get("/" + REALM + "/Leagues/" + enc(league) + "/Currencies/ByCategory"
                + "?category=" + enc(category) + "&page=" + page + "&perPage=" + perPage);
    }

    public JsonNode uniquesByCategory(String league, String category, int page, int perPage) {
        return get("/" + REALM + "/Leagues/" + enc(league) + "/Uniques/ByCategory"
                + "?category=" + enc(category) + "&page=" + page + "&perPage=" + perPage);
    }

    /** OHLC + volume giornaliero. E' il cuore del progetto. */
    public JsonNode dailyStats(String league, int itemId, int dayCount) {
        return get("/" + REALM + "/Leagues/" + enc(league) + "/Items/" + itemId
                + "/DailyStatsHistory?dayCount=" + dayCount);
    }

    // --- trasporto --------------------------------------------------------

    public JsonNode get(String path) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(BASE + path).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Accept-Encoding", "gzip");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);

                int sc = conn.getResponseCode();

                if (sc == 404) return null;

                if (sc == 429 || sc >= 500) {
                    long wait = parseRetryAfter(conn.getHeaderField("Retry-After"));
                    if (wait <= 0) wait = (long) (Math.pow(2, attempt) * 500);
                    System.err.println("  [" + sc + "] attendo " + wait + "ms e riprovo: " + path);
                    drain(conn);
                    sleep(wait);
                    continue;
                }

                String body = readBody(conn, sc >= 400);
                if (sc == 200) return mapper.readTree(body);

                throw new IllegalStateException("HTTP " + sc + " su " + path + " -> " + trunc(body));

            } catch (IOException e) {
                last = new RuntimeException("I/O su " + path, e);
                sleep((long) (Math.pow(2, attempt) * 500));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw (last != null ? last : new RuntimeException(MAX_ATTEMPTS + " tentativi falliti per " + path));
    }

    private static String readBody(HttpURLConnection conn, boolean isError) throws IOException {
        InputStream raw = isError ? conn.getErrorStream() : conn.getInputStream();
        if (raw == null) return "";
        try (InputStream in = "gzip".equalsIgnoreCase(conn.getContentEncoding())
                ? new GZIPInputStream(raw) : raw) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void drain(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream()) {
            if (in != null) in.readAllBytes();
        } catch (IOException ignored) {
            // niente da fare
        }
    }

    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long wait = lastCallAt + minIntervalMs - now;
        if (wait > 0) sleep(wait);
        lastCallAt = System.currentTimeMillis();
        requestCount++;
    }

    private static long parseRetryAfter(String v) {
        if (v == null) return 0L;
        try {
            return Long.parseLong(v.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
