package it.urbani.poe2economics.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import it.urbani.poe2economics.api.ScoutClient;
import it.urbani.poe2economics.store.Db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scarica lo storico giornaliero (OHLC + volume) di tutte le valute, per ogni lega.
 *
 * E' pensato per girare UNA volta e poi restare fermo: le leghe chiuse non cambiano piu'.
 * Solo la lega corrente va riaggiornata (--refresh-current).
 */
public final class Backfill {

    /**
     * L'API restituisce gli ULTIMI dayCount giorni: se una season fosse piu' lunga
     * perderebbe l'inizio, cioe' la parte su cui si fonda tutta l'analisi. La season
     * piu' lunga vista finora e' 165 giorni; 500 e' margine abbondante, e se qualche
     * serie ci arriva vicino il backfill lo dice invece di troncare in silenzio.
     */
    private static final int DAY_COUNT = 500;
    private boolean truncationWarned = false;
    private static final int PER_PAGE = 100;

    private final ScoutClient api;
    private final Db db;

    public Backfill(ScoutClient api, Db db) {
        this.api = api;
        this.db = db;
    }

    public void run(boolean includeHardcore, boolean refreshCurrent,
                    List<String> onlyCategories, boolean includeUniques) {
        List<Db.LeagueRow> leagues = fetchLeagues();
        List<Db.LeagueRow> targets = leagues.stream()
                // Hardcore permanente fuori; Standard resta perche' e' la sorgente
                // della season 0.1, che non ebbe una lega a tempo (vedi DERIVED)
                .filter(l -> !l.name().equalsIgnoreCase("Hardcore"))
                .filter(l -> l.name().equalsIgnoreCase("Standard") || includeHardcore || !isHardcore(l.name()))
                .toList();

        System.out.println("Leghe da processare: " + targets.size());
        targets.forEach(l -> System.out.println("  - " + l.name()
                + (l.current() ? "  [CORRENTE]" : "")
                + "   divinePrice=" + fmt(l.divinePrice())));
        System.out.println();

        for (Db.LeagueRow league : targets) {
            if (league.current() && refreshCurrent) {
                db.clearFetchLog(league.name());
            }
            processLeague(league, onlyCategories, includeUniques);
        }

        deriveSeasons();

        System.out.println();
        System.out.println("Righe daily_stat in archivio: " + db.countStats());
        System.out.println("Richieste HTTP totali: " + api.requestCount());
    }

    /**
     * Season che non ebbero una lega a tempo dedicata e il cui storico vive dentro Standard.
     *
     * PoE 2 e' uscito in Early Access l'8 dicembre 2024, ma la prima lega a tempo
     * (Dawn of the Hunt) e' partita solo il 5 aprile 2025: i quattro mesi in mezzo sono
     * la season 0.1, e per l'analisi vanno trattati come una season come le altre.
     */
    private record Derived(String name, String source, String from, String to) {}

    private static final List<Derived> DERIVED = List.of(
            new Derived("Early Access", "Standard", "2024-12-08", "2025-04-04"));

    /** Nomi usati in passato per le season derivate, da ripulire. */
    private static final List<String> LEGACY_DERIVED = List.of("Early Access 0.1");

    private void deriveSeasons() {
        for (String stale : LEGACY_DERIVED) db.deleteLeague(stale);
        for (Derived d : DERIVED) {
            int rows = db.deriveSeason(d.name(), d.source(), d.from(), d.to());
            System.out.println("Season derivata " + d.name() + " (" + d.source() + " "
                    + d.from() + " -> " + d.to() + "): " + rows + " righe");
        }
    }

    private List<Db.LeagueRow> fetchLeagues() {
        JsonNode arr = api.leagues();
        List<Db.LeagueRow> out = new ArrayList<>();
        for (JsonNode n : arr) {
            Db.LeagueRow l = new Db.LeagueRow(
                    n.path("Value").asText(),
                    n.path("ShortName").asText(""),
                    n.path("IsCurrent").asBoolean(false),
                    n.path("DivinePrice").asDouble(0));
            db.upsertLeague(l);
            out.add(l);
        }
        return out;
    }

    private void processLeague(Db.LeagueRow league, List<String> onlyCategories, boolean includeUniques) {
        System.out.println("=== " + league.name() + " ===");

        Categories cats = categories(league.name());
        List<String> currencyCats = filter(cats.currency(), onlyCategories);
        List<String> uniqueCats = includeUniques ? filter(cats.unique(), onlyCategories) : List.of();

        Map<Integer, Db.ItemRow> found = new LinkedHashMap<>();
        for (String cat : currencyCats) {
            found.putAll(itemsOfCategory(league.name(), cat, false));
        }
        for (String cat : uniqueCats) {
            found.putAll(itemsOfCategory(league.name(), cat, true));
        }
        db.upsertItems(new ArrayList<>(found.values()));
        System.out.println("  item trovati: " + found.size() + " su "
                + (currencyCats.size() + uniqueCats.size()) + " categorie"
                + (includeUniques ? " (unique inclusi)" : ""));

        int done = 0, skipped = 0, rows = 0, empty = 0;
        for (Db.ItemRow item : found.values()) {
            if (db.alreadyFetched(league.name(), item.itemId())) {
                skipped++;
                continue;
            }
            List<Db.StatRow> stats = dailyStats(league.name(), item.itemId());
            if (!stats.isEmpty()) {
                db.insertStats(league.name(), stats);
                rows += stats.size();
            } else {
                empty++;
            }
            db.logFetch(league.name(), item.itemId(), stats.size());
            done++;
            if (done % 25 == 0) {
                System.out.println("  ... " + done + "/" + (found.size() - skipped)
                        + "  (" + rows + " righe)");
            }
        }
        System.out.println("  scaricati " + done + ", gia' presenti " + skipped
                + ", senza storico " + empty + ", righe nuove " + rows);
        System.out.println();
    }

    private record Categories(List<String> currency, List<String> unique) {}

    private Categories categories(String league) {
        JsonNode node = api.categories(league);
        Set<String> cur = new LinkedHashSet<>();
        Set<String> uni = new LinkedHashSet<>();
        if (node != null) {
            for (JsonNode c : node.path("CurrencyCategories")) {
                String id = c.path("ApiId").asText("");
                if (!id.isBlank()) cur.add(id);
            }
            for (JsonNode c : node.path("UniqueCategories")) {
                String id = c.path("ApiId").asText("");
                if (!id.isBlank()) uni.add(id);
            }
        }
        return new Categories(new ArrayList<>(cur), new ArrayList<>(uni));
    }

    private static List<String> filter(List<String> all, List<String> only) {
        if (only == null || only.isEmpty()) return all;
        return all.stream().filter(only::contains).toList();
    }

    /**
     * Gli unique hanno una forma diversa dalle valute: niente ApiId, il nome sta in "Name"
     * ("The Dancing Dervish") mentre "Text" include il base type ("... Scimitar").
     */
    private Map<Integer, Db.ItemRow> itemsOfCategory(String league, String category, boolean unique) {
        Map<Integer, Db.ItemRow> out = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            JsonNode node = unique
                    ? api.uniquesByCategory(league, category, page, PER_PAGE)
                    : api.currenciesByCategory(league, category, page, PER_PAGE);
            if (node == null) break;
            for (JsonNode i : node.path("Items")) {
                int id = i.path("ItemId").asInt(0);
                if (id == 0) continue;
                String apiId = unique ? "unique-" + id : i.path("ApiId").asText("");
                String name = unique
                        ? firstNonBlank(i.path("Name").asText(""), i.path("Text").asText(""))
                        : i.path("Text").asText("");
                out.put(id, new Db.ItemRow(id, apiId, name,
                        unique ? "unique:" + category : category));
            }
            int pages = node.path("Pages").asInt(1);
            if (page >= pages) break;
            page++;
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private List<Db.StatRow> dailyStats(String league, int itemId) {
        JsonNode node = api.dailyStats(league, itemId, DAY_COUNT);
        List<Db.StatRow> out = new ArrayList<>();
        if (node == null) return out;
        int returned = node.path("DailyStats").size();
        if (returned >= DAY_COUNT && !truncationWarned) {
            truncationWarned = true;
            System.err.println("  ATTENZIONE: una serie ha restituito " + returned
                    + " giorni, il massimo richiesto. Lo storico potrebbe essere troncato:"
                    + " alza DAY_COUNT.");
        }
        for (JsonNode d : node.path("DailyStats")) {
            String day = d.path("Time").asText("");
            if (day.isBlank()) continue;
            if (day.length() > 10) day = day.substring(0, 10);
            out.add(new Db.StatRow(
                    itemId,
                    day,
                    d.path("Open").asDouble(0),
                    d.path("High").asDouble(0),
                    d.path("Low").asDouble(0),
                    d.path("Close").asDouble(0),
                    d.path("Average").asDouble(0),
                    d.path("Volume").asLong(0)));
        }
        return out;
    }

    private static boolean isHardcore(String name) {
        return name.startsWith("HC ") || name.equalsIgnoreCase("Hardcore");
    }

    private static boolean isPermanent(String name) {
        return name.equalsIgnoreCase("Standard") || name.equalsIgnoreCase("Hardcore");
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
