package it.urbani.poe2economics;

import it.urbani.poe2economics.analysis.Backtest;
import it.urbani.poe2economics.api.ScoutClient;
import it.urbani.poe2economics.backfill.Backfill;
import it.urbani.poe2economics.export.Exporter;
import it.urbani.poe2economics.store.Db;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class Main {

    private static final Path DB_FILE = Path.of("data", "poe2.db");
    private static final Path OUT_DIR = Path.of("out");

    /** Leghe permanenti: il "giorno di lega" non ha senso, si escludono dal backtest. */
    private static final Set<String> PERMANENT = Set.of("Standard", "Hardcore");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Args a = new Args(args);
        String cmd = args[0];

        switch (cmd) {
            case "backfill" -> backfill(a);
            case "leagues" -> leagues();
            case "backtest" -> backtest(a);
            case "scan" -> scan(a);
            case "export" -> export(a);
            default -> usage();
        }
    }

    // --- comandi ----------------------------------------------------------

    private static void backfill(Args a) {
        String contact = contact();
        long interval = a.getLong("--interval-ms", 300);
        System.out.println("User-Agent contact: " + maskContact(contact));
        System.out.println("Intervallo minimo fra richieste: " + interval + " ms");
        System.out.println();

        List<String> cats = a.getList("--categories");
        try (Db db = new Db(DB_FILE)) {
            ScoutClient api = new ScoutClient(contact, interval);
            new Backfill(api, db).run(a.has("--hardcore"), a.has("--refresh-current"),
                    cats, a.has("--uniques"));
        }
    }

    private static void leagues() {
        try (Db db = new Db(DB_FILE)) {
            Map<String, String[]> spans = db.leagueSpans();
            if (spans.isEmpty()) {
                System.out.println("Nessun dato. Lancia prima: backfill");
                return;
            }
            System.out.printf("%-24s %-12s %-12s %6s %6s %8s%n",
                    "LEGA", "INIZIO", "FINE", "GIORNI", "ITEM", "CORRENTE");
            System.out.println("-".repeat(76));
            Set<String> current = new HashSet<>();
            db.leagues().forEach(l -> { if (l.current()) current.add(l.name()); });
            spans.forEach((name, s) -> {
                long days = ChronoUnit.DAYS.between(LocalDate.parse(s[0]), LocalDate.parse(s[1]));
                System.out.printf("%-24s %-12s %-12s %6d %6s %8s%n",
                        name, s[0], s[1], days, s[3], current.contains(name) ? "si" : "");
            });
            System.out.println();
            System.out.println("Righe daily_stat: " + db.countStats());
        }
    }

    private static void backtest(Args a) throws Exception {
        int day = a.getInt("--day", 1);
        int horizon = a.getInt("--horizon", 14);
        String quote = a.get("--quote", "exalted");
        long minVol = a.getLong("--min-volume", 100);
        int minLeagues = a.getInt("--min-leagues", 2);
        int top = a.getInt("--top", 30);
        String category = a.get("--category", null);

        try (Db db = new Db(DB_FILE)) {
            Backtest bt = new Backtest(db);
            Collection<String> leagues = temporaryLeagues(bt.leagueLengths().keySet(), a.has("--include-hc"));
            if (leagues.isEmpty()) {
                System.out.println("Nessuna lega temporanea in archivio. Lancia prima: backfill");
                return;
            }
            Backtest.Fill fill = fillMode(a);
            System.out.println("Leghe nel campione: " + leagues);
            System.out.println("Riempimento: " + fill);

            List<Backtest.Result> rows =
                    bt.run(day, horizon, quote, minVol, minLeagues, category, leagues, fill);
            Backtest.print(rows, day, horizon, quote, top);
            Backtest.printWorst(rows, Math.min(10, top));

            if (a.has("--csv")) {
                Path f = OUT_DIR.resolve("backtest_d" + day + "_h" + horizon + "_" + quote + ".csv");
                Backtest.writeCsv(f, rows, day, horizon, quote);
                System.out.println();
                System.out.println("CSV scritto in " + f.toAbsolutePath());
            }
            disclaimer(leagues.size());
        }
    }

    private static void scan(Args a) {
        String quote = a.get("--quote", "exalted");
        long minVol = a.getLong("--min-volume", 100);
        int minLeagues = a.getInt("--min-leagues", 2);
        int top = a.getInt("--top", 3);
        String category = a.get("--category", null);

        int[] entryDays = a.getIntArray("--days", new int[]{1, 3, 5, 7, 10, 14, 21, 30});
        int[] horizons = a.getIntArray("--horizons", new int[]{7, 14, 30});

        try (Db db = new Db(DB_FILE)) {
            Backtest bt = new Backtest(db);
            Collection<String> leagues = temporaryLeagues(bt.leagueLengths().keySet(), a.has("--include-hc"));
            if (leagues.isEmpty()) {
                System.out.println("Nessuna lega temporanea in archivio. Lancia prima: backfill");
                return;
            }
            Backtest.Fill fill = fillMode(a);
            System.out.println("Leghe nel campione: " + leagues);
            System.out.println("Quote: " + quote + "   volume minimo: " + minVol
                    + "   leghe minime: " + minLeagues + "   riempimento: " + fill);

            for (int h : horizons) {
                System.out.println();
                System.out.println("=== orizzonte " + h + " giorni ===");
                for (int d : entryDays) {
                    List<Backtest.Result> rows =
                            bt.run(d, h, quote, minVol, minLeagues, category, leagues, fill);
                    if (rows.isEmpty()) {
                        System.out.printf("  giorno %-3d  (nessun item passa i filtri)%n", d);
                        continue;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(top, rows.size()); i++) {
                        Backtest.Result r = rows.get(i);
                        if (i > 0) sb.append("   |   ");
                        sb.append(String.format(Locale.ROOT, "%s %+.0f%% (n=%d, worst %+.0f%%)",
                                r.name(), r.median() * 100, r.n(), r.worst() * 100));
                    }
                    System.out.printf("  giorno %-3d  %s%n", d, sb);
                }
            }
            disclaimer(leagues.size());
        }
    }

    private static void export(Args a) throws Exception {
        String quote = a.get("--quote", "divine");
        long minVol = a.getLong("--min-volume", 200);
        int minLeagues = a.getInt("--min-leagues", 3);
        Path out = Path.of(a.get("--out", "site/data"));
        // il default e' la griglia che il sito offre: cosi' un export nudo basta,
        // e il workflow non deve ripetere gli argomenti per restare allineato
        int[] days = a.getIntArray("--days",
                new int[]{1, 3, 5, 7, 10, 14, 21, 30, 45, 60, 75, 90, 105, 120});
        int[] horizons = a.getIntArray("--horizons", new int[]{7, 14, 30});

        try (Db db = new Db(DB_FILE)) {
            Collection<String> leagues =
                    temporaryLeagues(db.leagueSpans().keySet(), a.has("--include-hc"));
            if (leagues.isEmpty()) {
                System.out.println("Nessuna lega temporanea in archivio. Lancia prima: backfill");
                return;
            }
            Backtest.Fill fill = fillMode(a);

            // di default si esportano solo gli item che esistono ancora nella patch
            // corrente: uno che il gioco ha rimosso non e' azionabile per nessuno
            Set<Integer> keep = null;
            if (!a.has("--all-items")) {
                List<String> current = db.leagues().stream()
                        .filter(Db.LeagueRow::current)
                        .map(Db.LeagueRow::name)
                        .filter(leagues::contains)
                        .toList();
                if (current.isEmpty()) {
                    System.out.println("Nessuna lega corrente in archivio: esporto tutti gli item.");
                } else {
                    keep = db.itemIdsWithData(current);
                    System.out.println("Filtro patch corrente " + current + ": " + keep.size()
                            + " item su " + db.items().size() + " totali in archivio");
                }
            }

            System.out.println("Esporto in " + out.toAbsolutePath());
            Exporter ex = new Exporter();
            ex.exportIndex(db, out, days, horizons, leagues, keep);

            if (!a.has("--no-series")) {
                System.out.println("Serie storiche:");
                // solo leghe temporanee: Standard non ha un giorno di lega e non interessa
                ex.exportSeries(db, out.resolve("series"), leagues, keep);
            }

            // il backtest per finestre non serve al sito: si esporta a richiesta
            if (a.has("--windows")) {
                System.out.println("Finestre (quote " + quote + ", riempimento " + fill
                        + ", volume >= " + minVol + ", season >= " + minLeagues + "):");
                ex.exportWindows(db, new Backtest(db), out, quote, fill, minVol, minLeagues,
                        days, horizons, leagues, keep);
            }
        }
    }

    // --- supporto ---------------------------------------------------------

    private static Collection<String> temporaryLeagues(Collection<String> all, boolean includeHc) {
        List<String> out = new ArrayList<>();
        for (String name : all) {
            if (PERMANENT.contains(name)) continue;
            if (!includeHc && name.startsWith("HC ")) continue;
            out.add(name);
        }
        return out;
    }

    private static Backtest.Fill fillMode(Args a) {
        String v = a.get("--fill", "avg");
        return "pessimistic".equalsIgnoreCase(v) || "pess".equalsIgnoreCase(v)
                ? Backtest.Fill.PESSIMISTIC : Backtest.Fill.AVG;
    }

    private static void disclaimer(int n) {
        System.out.println();
        System.out.println("!! n = " + n + " leghe. Non e' un campione, e fra una lega e l'altra");
        System.out.println("   GGG ha cambiato patch, drop rate e meccaniche. Questi numeri dicono");
        System.out.println("   cosa E' SUCCESSO, non cosa succedera'. Guarda sempre la colonna");
        System.out.println("   PEGGIORE e il volume prima di crederci.");
    }

    /** Nel log basta sapere che il contatto c'e' ed e' quello giusto, non leggerlo. */
    private static String maskContact(String c) {
        int at = c.indexOf('@');
        if (at <= 1) return c.isBlank() ? "unset" : "***";
        return c.charAt(0) + "***" + c.substring(at);
    }

    private static String contact() {
        String c = System.getenv("POE2SCOUT_CONTACT");
        if (c == null || c.isBlank()) {
            System.out.println("ATTENZIONE: variabile POE2SCOUT_CONTACT non impostata.");
            System.out.println("poe2scout chiede uno User-Agent con un contatto per l'uso continuativo.");
            System.out.println("Impostala prima del backfill:  export POE2SCOUT_CONTACT='tua@email'");
            System.out.println();
            return "unset";
        }
        return c;
    }

    private static void usage() {
        System.out.println("""
            PoE2 Economics - backfill e backtest sull'economia di Path of Exile 2

            COMANDI
              backfill    scarica lo storico giornaliero da poe2scout (una volta sola)
                            solo leghe temporanee: Standard e Hardcore sono esclusi,
                            non hanno un "giorno di lega" e non servono all'analisi
                            --categories currency,fragments   limita le categorie
                            --uniques                         includi anche gli unique (lento)
                            --hardcore                        includi le leghe HC temporanee
                            --refresh-current                 riscarica la lega in corso
                            --interval-ms 300                 pausa fra le richieste

              leagues     mostra le leghe in archivio, durata e copertura

              backtest    rendimento di ogni item contro un quote, fra due giorni di lega
                            --day 1           giorno di entrata
                            --horizon 14      giorni di permanenza
                            --quote exalted   exalted | chaos | divine | <apiId>
                            --min-volume 100  volume minimo in entrata e in uscita
                            --min-leagues 2   leghe minime in cui l'item deve esistere
                            --category currency
                            --fill avg        avg | pessimistic (compra al max, vende al min)
                            --top 30
                            --csv             esporta in out/

              scan        griglia giorno-di-entrata x orizzonte, per trovare le finestre
                            --days 1,3,5,7,10,14,21,30
                            --horizons 7,14,30
                            --quote exalted --min-volume 100 --top 3

              export      scrive il JSON statico per il sito
                            --out site/data
                            --windows         esporta anche il backtest per finestre
                                              (42 file, non usati dal sito)
                            --quote divine --fill pessimistic
                            --min-volume 200 --min-leagues 3
                            --days 1,3,5,7,10,14,21,30,45,60 --horizons 7,14,30
                            --all-items       non filtrare sulla patch corrente

            ESEMPIO
              export POE2SCOUT_CONTACT='tua@email'
              java -jar target/poe2economics.jar backfill --categories currency
              java -jar target/poe2economics.jar scan
              java -jar target/poe2economics.jar backtest --day 3 --horizon 14 --csv
            """);
    }

    /** Parser di argomenti minimale. */
    private static final class Args {
        private final Map<String, String> flags = new HashMap<>();
        private final Set<String> present = new HashSet<>();

        Args(String[] argv) {
            for (int i = 1; i < argv.length; i++) {
                String s = argv[i];
                if (!s.startsWith("--")) continue;
                present.add(s);
                if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    flags.put(s, argv[i + 1]);
                    i++;
                }
            }
        }

        boolean has(String k) { return present.contains(k); }

        String get(String k, String def) { return flags.getOrDefault(k, def); }

        int getInt(String k, int def) {
            String v = flags.get(k);
            return v == null ? def : Integer.parseInt(v.trim());
        }

        long getLong(String k, long def) {
            String v = flags.get(k);
            return v == null ? def : Long.parseLong(v.trim());
        }

        List<String> getList(String k) {
            String v = flags.get(k);
            if (v == null || v.isBlank()) return List.of();
            return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        int[] getIntArray(String k, int[] def) {
            String v = flags.get(k);
            if (v == null || v.isBlank()) return def;
            return Arrays.stream(v.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        }
    }
}
