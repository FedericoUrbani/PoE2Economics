package it.urbani.poe2economics.analysis;

import it.urbani.poe2economics.store.Db;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Backtest per coppie.
 *
 * Idea di fondo: in PoE non "possiedi valore", possiedi una cosa invece di un'altra.
 * Quindi il rendimento non e' "quanto e' salito X" ma "quanto e' salito X rispetto a Y",
 * fra il giorno D e il giorno D+K della lega. Tutti i prezzi dell'API sono gia'
 * denominati in Exalted Orb, quindi il quote "exalted" vale 1.0 per definizione.
 */
public final class Backtest {

    /**
     * Come si assume di riempire gli ordini.
     * AVG usa il prezzo medio giornaliero: comodo, ottimista.
     * PESSIMISTIC compra l'item al massimo di giornata e lo rivende al minimo, e fa
     * l'opposto sul quote: e' il caso peggiore, ed e' l'unico numero che puoi mostrare
     * a qualcuno senza sentirti in colpa.
     */
    public enum Fill { AVG, PESSIMISTIC }

    /** Un punto della serie giornaliera. */
    private record Point(double avg, double low, double high, long volume) {}

    /** Esito su una singola lega. */
    public record Obs(String league, double ret, long volEntry, long volExit) {}

    /** Riga di risultato aggregata su piu' leghe. */
    public record Result(int itemId, String name, String category, int n,
                         double median, double worst, double best,
                         long medVolume, List<Obs> obs) {}

    private final Map<String, Map<Integer, NavigableMap<Integer, Point>>> data = new LinkedHashMap<>();
    private final Map<String, LocalDate> leagueStart = new LinkedHashMap<>();
    private final Map<String, Integer> leagueLength = new LinkedHashMap<>();
    private final Map<Integer, Db.ItemRow> items;
    private final Map<String, Integer> apiIdToItemId = new HashMap<>();

    private static final int DAY_TOLERANCE = 2;

    public Backtest(Db db) {
        this.items = db.items();
        for (Db.ItemRow it : items.values()) {
            apiIdToItemId.putIfAbsent(it.apiId(), it.itemId());
        }

        Map<String, String[]> spans = db.leagueSpans();
        spans.forEach((league, s) -> {
            leagueStart.put(league, LocalDate.parse(s[0]));
            leagueLength.put(league, (int) ChronoUnit.DAYS.between(
                    LocalDate.parse(s[0]), LocalDate.parse(s[1])));
        });

        db.forEachStat((league, itemId, day, avg, low, high, volume) -> {
            LocalDate start = leagueStart.get(league);
            if (start == null || avg <= 0) return;
            int idx = (int) ChronoUnit.DAYS.between(start, LocalDate.parse(day));
            data.computeIfAbsent(league, k -> new HashMap<>())
                    .computeIfAbsent(itemId, k -> new TreeMap<>())
                    .put(idx, new Point(avg, low, high, volume));
        });
    }

    public Map<String, Integer> leagueLengths() {
        return leagueLength;
    }

    // --- API di analisi ---------------------------------------------------

    public List<Result> run(int entryDay, int horizon, String quoteApiId,
                            long minVolume, int minLeagues, String categoryFilter,
                            Collection<String> leagues) {
        return run(entryDay, horizon, quoteApiId, minVolume, minLeagues, categoryFilter,
                leagues, Fill.AVG);
    }

    public List<Result> run(int entryDay, int horizon, String quoteApiId,
                            long minVolume, int minLeagues, String categoryFilter,
                            Collection<String> leagues, Fill fill) {

        Integer quoteId = apiIdToItemId.get(quoteApiId);
        boolean quoteIsExalted = "exalted".equalsIgnoreCase(quoteApiId);
        if (!quoteIsExalted && quoteId == null) {
            throw new IllegalArgumentException("quote sconosciuto: " + quoteApiId);
        }

        List<Result> out = new ArrayList<>();

        for (Db.ItemRow item : items.values()) {
            if (!quoteIsExalted && quoteId != null && item.itemId() == quoteId) continue;
            if (categoryFilter != null && !categoryFilter.equalsIgnoreCase(item.category())) continue;

            List<Obs> obs = new ArrayList<>();
            List<Long> vols = new ArrayList<>();

            for (String league : leagues) {
                Map<Integer, NavigableMap<Integer, Point>> perItem = data.get(league);
                if (perItem == null) continue;

                Point aIn = at(perItem, item.itemId(), entryDay);
                Point aOut = at(perItem, item.itemId(), entryDay + horizon);
                if (aIn == null || aOut == null) continue;
                if (aIn.volume() < minVolume || aOut.volume() < minVolume) continue;

                // prezzo dell'item: in entrata si compra caro, in uscita si vende a poco
                double aInPrice = fill == Fill.PESSIMISTIC ? pos(aIn.high(), aIn.avg()) : aIn.avg();
                double aOutPrice = fill == Fill.PESSIMISTIC ? pos(aOut.low(), aOut.avg()) : aOut.avg();

                // prezzo del quote: specularmente sfavorevole
                double qIn = 1.0, qOut = 1.0;
                if (!quoteIsExalted) {
                    Point p1 = at(perItem, quoteId, entryDay);
                    Point p2 = at(perItem, quoteId, entryDay + horizon);
                    if (p1 == null || p2 == null) continue;
                    qIn = fill == Fill.PESSIMISTIC ? pos(p1.low(), p1.avg()) : p1.avg();
                    qOut = fill == Fill.PESSIMISTIC ? pos(p2.high(), p2.avg()) : p2.avg();
                }
                if (qIn <= 0 || qOut <= 0 || aInPrice <= 0 || aOutPrice <= 0) continue;

                double ratioIn = aInPrice / qIn;
                double ratioOut = aOutPrice / qOut;
                if (ratioIn <= 0) continue;

                obs.add(new Obs(league, ratioOut / ratioIn - 1.0, aIn.volume(), aOut.volume()));
                vols.add(Math.min(aIn.volume(), aOut.volume()));
            }

            if (obs.size() < minLeagues) continue;

            double[] rets = obs.stream().mapToDouble(Obs::ret).sorted().toArray();
            out.add(new Result(item.itemId(), item.name(), item.category(), obs.size(),
                    median(rets), rets[0], rets[rets.length - 1], medianL(vols), obs));
        }

        out.sort(Comparator.comparingDouble(Result::median).reversed());
        return out;
    }

    private Point at(Map<Integer, NavigableMap<Integer, Point>> perItem, int itemId, int day) {
        NavigableMap<Integer, Point> series = perItem.get(itemId);
        if (series == null) return null;
        Point exact = series.get(day);
        if (exact != null) return exact;
        Map.Entry<Integer, Point> lo = series.floorEntry(day);
        Map.Entry<Integer, Point> hi = series.ceilingEntry(day);
        Map.Entry<Integer, Point> best = null;
        int bestDist = Integer.MAX_VALUE;
        if (lo != null && day - lo.getKey() <= DAY_TOLERANCE) {
            best = lo;
            bestDist = day - lo.getKey();
        }
        if (hi != null && hi.getKey() - day <= DAY_TOLERANCE && hi.getKey() - day < bestDist) {
            best = hi;
        }
        return best == null ? null : best.getValue();
    }

    /** Alcune giornate hanno low/high a zero: in quel caso si ricade sulla media. */
    private static double pos(double preferred, double fallback) {
        return preferred > 0 ? preferred : fallback;
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        if (n == 0) return 0;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static long medianL(List<Long> v) {
        if (v.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    // --- stampa -----------------------------------------------------------

    public static void print(List<Result> rows, int entryDay, int horizon, String quote, int top) {
        System.out.println();
        System.out.printf("Entrata giorno %d  ->  uscita giorno %d   (orizzonte %d gg)   quote: %s%n",
                entryDay, entryDay + horizon, horizon, quote);
        System.out.println("Rendimento = quanto rende TENERE l'item invece del quote, fra i due giorni.");
        System.out.println("-".repeat(132));
        System.out.printf("%-32s %-20s %3s %10s %10s %10s %9s  %s%n",
                "ITEM", "CATEGORIA", "n", "MEDIANA", "PEGGIORE", "MIGLIORE", "VOL", "PER LEGA");
        System.out.println("-".repeat(132));

        int shown = 0;
        for (Result r : rows) {
            if (shown++ >= top) break;
            System.out.printf("%-32s %-20s %3d %10s %10s %10s %9d  %s%n",
                    cut(r.name(), 32), cut(r.category(), 20), r.n(),
                    pct(r.median()), pct(r.worst()), pct(r.best()), r.medVolume(),
                    detail(r.obs()));
        }
        System.out.println("-".repeat(132));
        System.out.println("righe che passano i filtri: " + rows.size());
    }

    public static void printWorst(List<Result> rows, int top) {
        System.out.println();
        System.out.println("### Da NON tenere in questa finestra (mediana peggiore) ###");
        List<Result> rev = new ArrayList<>(rows);
        Collections.reverse(rev);
        int shown = 0;
        for (Result r : rev) {
            if (shown++ >= top) break;
            System.out.printf("%-32s %-20s %3d %10s   %s%n",
                    cut(r.name(), 32), cut(r.category(), 20), r.n(), pct(r.median()), detail(r.obs()));
        }
    }

    public static void writeCsv(Path file, List<Result> rows, int entryDay, int horizon, String quote)
            throws IOException {
        Files.createDirectories(file.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("item_id,name,category,quote,entry_day,exit_day,n,median,worst,best,median_volume,per_league");
            for (Result r : rows) {
                w.printf(Locale.ROOT, "%d,\"%s\",%s,%s,%d,%d,%d,%.6f,%.6f,%.6f,%d,\"%s\"%n",
                        r.itemId(), r.name().replace("\"", "'"), r.category(), quote,
                        entryDay, entryDay + horizon, r.n(),
                        r.median(), r.worst(), r.best(), r.medVolume(), detail(r.obs()));
            }
        }
    }

    private static String detail(List<Obs> obs) {
        StringBuilder sb = new StringBuilder();
        for (Obs o : obs) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(shortLeague(o.league())).append(":").append(pct(o.ret()));
        }
        return sb.toString();
    }

    private static String shortLeague(String name) {
        return switch (name) {
            case "Dawn of the Hunt" -> "hunt";
            case "Rise of the Abyssal" -> "abyss";
            case "Fate of the Vaal" -> "vaal";
            case "Runes of Aldur" -> "runes";
            case "Early Access" -> "ea01";
            case "Standard" -> "std";
            default -> name.length() > 8 ? name.substring(0, 8) : name;
        };
    }

    private static String pct(double d) {
        return String.format(Locale.ROOT, "%+.1f%%", d * 100);
    }

    /** Niente ellissi Unicode: la console di Windows non la rende. */
    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 2) + "..";
    }
}
