package it.urbani.poe2economics.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.urbani.poe2economics.analysis.Backtest;
import it.urbani.poe2economics.store.Db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Scrive il risultato del backtest come JSON statico, un file per combinazione
 * (giorno di entrata x orizzonte). Il sito non ha bisogno di un backend: carica
 * index.json, poi solo il file della combinazione che l'utente sta guardando.
 */
public final class Exporter {

    private final ObjectMapper mapper = new ObjectMapper();

    /** index.json: le season, la griglia di giorni offerta dal sito e i metadati. */
    public void exportIndex(Db db, Path outDir, int[] days, int[] horizons,
                            Collection<String> leagues, Set<Integer> keepItems) throws IOException {

        Files.createDirectories(outDir);

        // --- index.json ---------------------------------------------------
        Map<String, String[]> spans = db.leagueSpans();
        Set<String> currentNames = new LinkedHashSet<>();
        db.leagues().forEach(l -> { if (l.current()) currentNames.add(l.name()); });

        Map<String, String> shorts = shorts(leagues);
        Map<String, String> patches = patches(leagues);
        List<Map<String, Object>> leagueMeta = new ArrayList<>();
        Map<String, Object> current = null;
        for (String name : leagues) {
            String[] s = spans.get(name);
            if (s == null) continue;
            LocalDate from = LocalDate.parse(s[0]);
            LocalDate to = LocalDate.parse(s[1]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("short", shorts.get(name));
            m.put("patch", patches.get(name));
            m.put("start", s[0]);
            m.put("end", s[1]);
            m.put("days", (int) ChronoUnit.DAYS.between(from, to));
            m.put("items", Integer.parseInt(s[3]));
            m.put("current", currentNames.contains(name));
            leagueMeta.add(m);
            if (currentNames.contains(name)) current = m;
        }

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("generatedAt", Instant.now().toString());
        index.put("days", Arrays.stream(days).boxed().toList());
        index.put("horizons", Arrays.stream(horizons).boxed().toList());
        index.put("leagues", leagueMeta);
        index.put("currentLeague", current);
        index.put("dailyRows", db.countStats(leagues));
        index.put("itemScope", keepItems == null ? "all" : "current-patch");

        write(outDir.resolve("index.json"), index);
        System.out.println("  index.json: " + leagueMeta.size() + " season");
    }

    /**
     * Un file per combinazione giorno-di-entrata x orizzonte, con il risultato del
     * backtest. Il sito non li usa: servono a chi vuole i numeri gia' calcolati.
     */
    public void exportWindows(Db db, Backtest bt, Path outDir, String quote, Backtest.Fill fill,
                              long minVolume, int minLeagues, int[] days, int[] horizons,
                              Collection<String> leagues, Set<Integer> keepItems) throws IOException {
        Files.createDirectories(outDir);
        Map<String, String> shorts = shorts(leagues);
        int files = 0;
        for (int h : horizons) {
            for (int d : days) {
                List<Backtest.Result> rows =
                        bt.run(d, h, quote, minVolume, minLeagues, null, leagues, fill);

                List<Map<String, Object>> items = new ArrayList<>(rows.size());
                for (Backtest.Result r : rows) {
                    if (keepItems != null && !keepItems.contains(r.itemId())) continue;
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", r.itemId());
                    o.put("name", r.name());
                    o.put("cat", r.category());
                    o.put("n", r.n());
                    o.put("med", round(r.median()));
                    o.put("worst", round(r.worst()));
                    o.put("best", round(r.best()));
                    o.put("vol", r.medVolume());
                    List<Map<String, Object>> obs = new ArrayList<>(r.obs().size());
                    for (Backtest.Obs ob : r.obs()) {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("l", shorts.get(ob.league()));
                        x.put("r", round(ob.ret()));
                        obs.add(x);
                    }
                    o.put("obs", obs);
                    items.add(o);
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("day", d);
                payload.put("horizon", h);
                payload.put("quote", quote);
                payload.put("fill", fill.name().toLowerCase(Locale.ROOT));
                payload.put("minVolume", minVolume);
                payload.put("minLeagues", minLeagues);
                payload.put("items", items);

                write(outDir.resolve("d" + d + "_h" + h + ".json"), payload);
                files++;
                System.out.printf("  d%-3d h%-3d  %4d item%n", d, h, items.size());
            }
        }

        System.out.println("  finestre: " + files + " file");
    }

    /**
     * Serie storiche complete, un file per categoria.
     *
     * Ogni item porta, per ogni lega, il prezzo medio giornaliero (in Exalted) e il volume,
     * come array compatti indicizzati dal giorno di lega. La pagina converte in Divine
     * usando la serie del Divine Orb, che sta in _quotes.json.
     */
    public void exportSeries(Db db, Path outDir, Collection<String> leagues,
                             Set<Integer> keepItems) throws IOException {
        Files.createDirectories(outDir);

        Map<String, LocalDate> starts = new LinkedHashMap<>();
        db.leagueSpans().forEach((name, s) -> starts.put(name, LocalDate.parse(s[0])));

        Set<String> wanted = new LinkedHashSet<>(leagues);
        Map<String, String> shorts = shorts(leagues);
        Map<Integer, Db.ItemRow> items = db.items();

        // itemId -> lega -> giorno -> [prezzo, volume]
        Map<Integer, Map<String, NavigableMap<Integer, double[]>>> byItem = new HashMap<>();

        db.forEachStat((league, itemId, day, avg, low, high, volume) -> {
            if (!wanted.contains(league) || avg <= 0) return;
            LocalDate start = starts.get(league);
            if (start == null) return;
            int idx = (int) ChronoUnit.DAYS.between(start, LocalDate.parse(day));
            byItem.computeIfAbsent(itemId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(league, k -> new TreeMap<>())
                    .put(idx, new double[]{avg, volume});
        });

        Map<String, List<Map<String, Object>>> byCategory = new TreeMap<>();
        for (Map.Entry<Integer, Map<String, NavigableMap<Integer, double[]>>> e : byItem.entrySet()) {
            Db.ItemRow item = items.get(e.getKey());
            if (item == null) continue;
            if (keepItems != null && !keepItems.contains(item.itemId())) continue;
            byCategory.computeIfAbsent(item.category(), k -> new ArrayList<>())
                    .add(itemPayload(item, e.getValue(), shorts));
        }

        List<Map<String, Object>> catIndex = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byCategory.entrySet()) {
            List<Map<String, Object>> list = e.getValue();
            list.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("category", e.getKey());
            payload.put("items", list);

            Path f = outDir.resolve(slug(e.getKey()) + ".json");
            write(f, payload);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("category", e.getKey());
            meta.put("file", slug(e.getKey()) + ".json");
            meta.put("items", list.size());
            catIndex.add(meta);
            System.out.printf("  %-24s %4d item%n", e.getKey(), list.size());
        }

        // le serie usate come denominatore, sempre caricate dalla pagina
        Map<String, Object> quotes = new LinkedHashMap<>();
        for (String apiId : List.of("divine", "chaos")) {
            Integer id = items.values().stream()
                    .filter(i -> apiId.equals(i.apiId()))
                    .map(Db.ItemRow::itemId).findFirst().orElse(null);
            if (id == null || !byItem.containsKey(id)) continue;
            quotes.put(apiId, itemPayload(items.get(id), byItem.get(id), shorts));
        }
        write(outDir.resolve("_quotes.json"), quotes);

        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("categories", catIndex);
        write(outDir.resolve("_index.json"), idx);

        System.out.println("  serie storiche: " + (catIndex.size() + 2) + " file in "
                + outDir.toAbsolutePath());
    }

    private Map<String, Object> itemPayload(Db.ItemRow item,
                                            Map<String, NavigableMap<Integer, double[]>> perLeague,
                                            Map<String, String> shorts) {
        Map<String, Object> series = new LinkedHashMap<>();
        for (Map.Entry<String, NavigableMap<Integer, double[]>> le : perLeague.entrySet()) {
            NavigableMap<Integer, double[]> days = le.getValue();
            if (days.isEmpty()) continue;
            int from = days.firstKey(), to = days.lastKey();
            List<Double> price = new ArrayList<>(to - from + 1);
            List<Long> vol = new ArrayList<>(to - from + 1);
            for (int d = from; d <= to; d++) {
                double[] p = days.get(d);
                price.add(p == null ? null : sig(p[0]));
                vol.add(p == null ? null : (long) p[1]);
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("from", from);
            s.put("p", price);
            s.put("v", vol);
            series.put(shorts.get(le.getKey()), s);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", item.itemId());
        o.put("name", item.name());
        o.put("cat", item.category());
        o.put("series", series);
        return o;
    }

    /** Quattro cifre significative: taglia il peso del JSON senza perdere niente di utile. */
    private static Double sig(double v) {
        if (v == 0) return 0.0;
        double mag = Math.pow(10, 3 - (int) Math.floor(Math.log10(Math.abs(v))));
        return Math.round(v * mag) / mag;
    }

    private static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private void write(Path file, Object value) throws IOException {
        mapper.writeValue(file.toFile(), value);
    }

    private static double round(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    /**
     * Nomi corti stabili. Le season note hanno il loro storico gia' esportato con
     * queste chiavi e non si toccano; una lega nuova prende uno slug automatico,
     * con suffisso numerico se due nomi iniziano con la stessa parola — altrimenti
     * due season finirebbero sotto la stessa chiave e i loro dati si fonderebbero.
     */
    private static final Map<String, String> KNOWN_SHORT = Map.of(
            "Early Access", "ea01",
            "Dawn of the Hunt", "hunt",
            "Rise of the Abyssal", "abyss",
            "Fate of the Vaal", "vaal",
            "Runes of Aldur", "runes");

    private static final Set<String> STOP_WORDS = Set.of("of", "the", "a", "an", "and");

    public static Map<String, String> shorts(Collection<String> leagues) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (String l : leagues) {
            String base = KNOWN_SHORT.getOrDefault(l, slugShort(l));
            String s = base;
            for (int n = 2; !used.add(s); n++) s = base + n;
            out.put(l, s);
        }
        return out;
    }

    /** Prima parola significativa del nome, minuscola, max 8 caratteri. */
    private static String slugShort(String league) {
        for (String w : league.split("\\s+")) {
            String clean = w.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (clean.isEmpty() || STOP_WORDS.contains(clean)) continue;
            return clean.length() > 8 ? clean.substring(0, 8) : clean;
        }
        return league.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Versione della patch di ogni season.
     *
     * Nessuna API la espone — controllati /Leagues, /Realms e LandingSplashInfo di
     * poe2scout, e la League ufficiale di GGG: nessuno porta un campo di versione.
     * Si deduce percio' dalla posizione, perche' PoE 2 ha spedito una minor per season
     * nell'ordine di uscita.
     *
     * L'override esplicito vince sulla deduzione: e' li' per correggere una season che
     * non seguisse la sequenza.
     */
    private static final Map<String, String> KNOWN_PATCH = Map.of(
            "Early Access", "0.1",
            "Dawn of the Hunt", "0.2",
            "Rise of the Abyssal", "0.3",
            "Fate of the Vaal", "0.4",
            "Runes of Aldur", "0.5");

    /** Le prime cinque season in Early Access sono le 0.1-0.5. */
    private static final int EARLY_ACCESS_SEASONS = 5;

    /**
     * Le prime cinque season sono le 0.1-0.5. La sesta e' la 1.0: l'uscita dall'Early
     * Access, che rompe la numerazione 0.x. Da li' in poi si assume una minor per
     * season come fa PoE 1 — 1.1, 1.2 — e resta l'override per correggere.
     */
    private static String derivePatch(int index) {
        return index < EARLY_ACCESS_SEASONS
                ? "0." + (index + 1)
                : "1." + (index - EARLY_ACCESS_SEASONS);
    }

    /** @param leagues in ordine di data di inizio, come le restituisce leagueSpans() */
    public static Map<String, String> patches(Collection<String> leagues) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        for (String l : leagues) {
            out.put(l, KNOWN_PATCH.getOrDefault(l, derivePatch(i)));
            i++;
        }
        return out;
    }

}
