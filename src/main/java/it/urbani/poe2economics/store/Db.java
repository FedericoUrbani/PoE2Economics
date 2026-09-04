package it.urbani.poe2economics.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Storage SQLite. Lo storico delle leghe chiuse non cambia piu': una volta scaricato e' tuo. */
public final class Db implements AutoCloseable {

    public record LeagueRow(String name, String shortName, boolean current, double divinePrice) {}

    public record ItemRow(int itemId, String apiId, String name, String category) {}

    public record StatRow(int itemId, String day, double open, double high, double low,
                          double close, double avg, long volume) {}

    private final Connection c;

    public Db(Path file) {
        try {
            // su un checkout pulito data/ non esiste (e' in .gitignore) e SQLite non
            // crea il file se manca la directory padre: e' il motivo per cui il primo
            // run del workflow falliva dopo 34 secondi
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);

            this.c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
            }
            schema();
        } catch (SQLException | java.io.IOException e) {
            throw new RuntimeException("apertura DB fallita: " + file, e);
        }
    }

    private void schema() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS league (
                  name          TEXT PRIMARY KEY,
                  short_name    TEXT,
                  is_current    INTEGER NOT NULL DEFAULT 0,
                  divine_price  REAL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS item (
                  item_id   INTEGER PRIMARY KEY,
                  api_id    TEXT NOT NULL,
                  name      TEXT NOT NULL,
                  category  TEXT NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS daily_stat (
                  league   TEXT    NOT NULL,
                  item_id  INTEGER NOT NULL,
                  day      TEXT    NOT NULL,
                  open     REAL,
                  high     REAL,
                  low      REAL,
                  close    REAL,
                  avg      REAL,
                  volume   INTEGER,
                  PRIMARY KEY (league, item_id, day)
                )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_stat_league_item ON daily_stat(league, item_id)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS fetch_log (
                  league     TEXT    NOT NULL,
                  item_id    INTEGER NOT NULL,
                  rows       INTEGER NOT NULL,
                  fetched_at TEXT    NOT NULL,
                  PRIMARY KEY (league, item_id)
                )""");
        }
    }

    // --- scritture --------------------------------------------------------

    public void upsertLeague(LeagueRow l) {
        exec("INSERT INTO league(name, short_name, is_current, divine_price) VALUES(?,?,?,?) "
                        + "ON CONFLICT(name) DO UPDATE SET short_name=excluded.short_name, "
                        + "is_current=excluded.is_current, divine_price=excluded.divine_price",
                ps -> {
                    ps.setString(1, l.name());
                    ps.setString(2, l.shortName());
                    ps.setInt(3, l.current() ? 1 : 0);
                    ps.setDouble(4, l.divinePrice());
                });
    }

    public void upsertItems(List<ItemRow> items) {
        String sql = "INSERT INTO item(item_id, api_id, name, category) VALUES(?,?,?,?) "
                + "ON CONFLICT(item_id) DO UPDATE SET api_id=excluded.api_id, "
                + "name=excluded.name, category=excluded.category";
        inTx(() -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (ItemRow i : items) {
                    ps.setInt(1, i.itemId());
                    ps.setString(2, i.apiId());
                    ps.setString(3, i.name());
                    ps.setString(4, i.category());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void insertStats(String league, List<StatRow> rows) {
        String sql = "INSERT INTO daily_stat(league,item_id,day,open,high,low,close,avg,volume) "
                + "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(league,item_id,day) DO UPDATE SET "
                + "open=excluded.open, high=excluded.high, low=excluded.low, "
                + "close=excluded.close, avg=excluded.avg, volume=excluded.volume";
        inTx(() -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (StatRow r : rows) {
                    ps.setString(1, league);
                    ps.setInt(2, r.itemId());
                    ps.setString(3, r.day());
                    ps.setDouble(4, r.open());
                    ps.setDouble(5, r.high());
                    ps.setDouble(6, r.low());
                    ps.setDouble(7, r.close());
                    ps.setDouble(8, r.avg());
                    ps.setLong(9, r.volume());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void logFetch(String league, int itemId, int rows) {
        exec("INSERT INTO fetch_log(league,item_id,rows,fetched_at) VALUES(?,?,?,datetime('now')) "
                        + "ON CONFLICT(league,item_id) DO UPDATE SET rows=excluded.rows, "
                        + "fetched_at=excluded.fetched_at",
                ps -> {
                    ps.setString(1, league);
                    ps.setInt(2, itemId);
                    ps.setInt(3, rows);
                });
    }

    public boolean alreadyFetched(String league, int itemId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM fetch_log WHERE league=? AND item_id=?")) {
            ps.setString(1, league);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Materializza una season derivata copiando una finestra di date da un'altra lega.
     * Serve per la 0.1 di Early Access, che non ebbe una lega a tempo: il suo storico
     * vive dentro Standard, fra il lancio e l'inizio di Dawn of the Hunt.
     *
     * @return righe copiate
     */
    public int deriveSeason(String newName, String sourceLeague, String fromDay, String toDay) {
        exec("INSERT INTO league(name, short_name, is_current, divine_price) VALUES(?,?,0,0) "
                        + "ON CONFLICT(name) DO NOTHING",
                ps -> {
                    ps.setString(1, newName);
                    ps.setString(2, newName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""));
                });
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily_stat(league,item_id,day,open,high,low,close,avg,volume) "
                        + "SELECT ?, item_id, day, open, high, low, close, avg, volume "
                        + "FROM daily_stat WHERE league=? AND day>=? AND day<=? "
                        + "ON CONFLICT(league,item_id,day) DO UPDATE SET "
                        + "open=excluded.open, high=excluded.high, low=excluded.low, "
                        + "close=excluded.close, avg=excluded.avg, volume=excluded.volume")) {
            ps.setString(1, newName);
            ps.setString(2, sourceLeague);
            ps.setString(3, fromDay);
            ps.setString(4, toDay);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("derivazione season fallita: " + newName, e);
        }
    }

    /** Rimuove del tutto una lega dall archivio: serve per le season derivate rinominate. */
    public void deleteLeague(String name) {
        exec("DELETE FROM daily_stat WHERE league=?", ps -> ps.setString(1, name));
        exec("DELETE FROM fetch_log WHERE league=?", ps -> ps.setString(1, name));
        exec("DELETE FROM league WHERE name=?", ps -> ps.setString(1, name));
    }

    public void clearFetchLog(String league) {
        exec("DELETE FROM fetch_log WHERE league=?", ps -> ps.setString(1, league));
    }

    // --- letture ----------------------------------------------------------

    public List<LeagueRow> leagues() {
        List<LeagueRow> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name, short_name, is_current, divine_price FROM league ORDER BY name")) {
            while (rs.next()) {
                out.add(new LeagueRow(rs.getString(1), rs.getString(2),
                        rs.getInt(3) == 1, rs.getDouble(4)));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public Map<Integer, ItemRow> items() {
        Map<Integer, ItemRow> out = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT item_id, api_id, name, category FROM item")) {
            while (rs.next()) {
                out.put(rs.getInt(1), new ItemRow(rs.getInt(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** league -> [primoGiorno, ultimoGiorno, nGiorni, nItem] */
    public Map<String, String[]> leagueSpans() {
        Map<String, String[]> out = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT league, MIN(day), MAX(day), COUNT(DISTINCT day), COUNT(DISTINCT item_id) "
                             + "FROM daily_stat GROUP BY league ORDER BY MIN(day)")) {
            while (rs.next()) {
                out.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3),
                        String.valueOf(rs.getInt(4)), String.valueOf(rs.getInt(5))});
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public interface StatConsumer {
        void accept(String league, int itemId, String day, double avg, double low, double high, long volume);
    }

    public void forEachStat(StatConsumer f) {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT league, item_id, day, avg, low, high, volume FROM daily_stat")) {
            while (rs.next()) {
                f.accept(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getLong(7));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Righe delle sole leghe indicate: l'archivio puo' contenere leghe che non esportiamo piu'. */
    public long countStats(Collection<String> leagues) {
        if (leagues == null || leagues.isEmpty()) return 0;
        String marks = String.join(",", java.util.Collections.nCopies(leagues.size(), "?"));
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM daily_stat WHERE league IN (" + marks + ")")) {
            int i = 1;
            for (String l : leagues) ps.setString(i++, l);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Item che hanno almeno una giornata di dati in una di quelle leghe. */
    public java.util.Set<Integer> itemIdsWithData(Collection<String> leagues) {
        java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
        if (leagues == null || leagues.isEmpty()) return out;
        String marks = String.join(",", java.util.Collections.nCopies(leagues.size(), "?"));
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT item_id FROM daily_stat WHERE league IN (" + marks + ")")) {
            int i = 1;
            for (String l : leagues) ps.setString(i++, l);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public long countStats() {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM daily_stat")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- utility ----------------------------------------------------------

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface TxBody {
        void run() throws SQLException;
    }

    private void exec(String sql, Binder b) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            b.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(sql, e);
        }
    }

    private void inTx(TxBody body) {
        try {
            c.setAutoCommit(false);
            body.run();
            c.commit();
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException(e);
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    @Override
    public void close() {
        try { c.close(); } catch (SQLException ignored) { }
    }
}
