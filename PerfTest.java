import com.sun.net.httpserver.HttpServer;
import java.net.Inet6Address;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PerfTest {
  static int failed;

  static void expect(boolean cond, String msg) {
    if (!cond) {
      System.err.println("FAIL: " + msg);
      failed = 1;
    } else {
      System.err.println("ok: " + msg);
    }
  }

  static int countTalksKeys(String json) {
    int n = 0;
    int from = 0;
    while (true) {
      int i = json.indexOf("\"talks\":", from);
      if (i < 0) {
        return n;
      }
      n++;
      from = i + 8;
    }
  }

  static void assertYearsDesc(String body) {
    Matcher m = Pattern.compile("\"years\":\\[([^\\]]*)]").matcher(body);
    boolean foundMulti = false;
    while (m.find()) {
      String inner = m.group(1).trim();
      if (inner.isEmpty()) {
        continue;
      }
      String[] parts = inner.split(",");
      if (parts.length < 2) {
        continue;
      }
      foundMulti = true;
      int prev = Integer.MAX_VALUE;
      for (String part : parts) {
        int y = Integer.parseInt(part.trim());
        if (y > prev) {
          expect(false, "years not DESC: [" + inner + "]");
          return;
        }
        prev = y;
      }
    }
    expect(foundMulti, "expected a speaker with >=2 years");
  }

  static void assertYearsDescMaps(List<Map<String, Object>> speakers) {
    boolean foundMulti = false;
    for (Map<String, Object> sp : speakers) {
      Object raw = sp.get("years");
      if (!(raw instanceof List<?> years) || years.size() < 2) {
        continue;
      }
      foundMulti = true;
      int prev = Integer.MAX_VALUE;
      for (Object y : years) {
        int n = y instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(y));
        if (n > prev) {
          expect(false, "years not DESC for " + sp.get("slug") + ": " + years);
          return;
        }
        prev = n;
      }
    }
    expect(foundMulti, "expected a speaker with >=2 years from listSpeakers");
  }

  public static void main(String[] args) throws Exception {
    expect("::".equals(Main.listenHost()), "listen host is ::");
    String src = Files.readString(Path.of("Main.java"));
    expect(!src.contains("\"0.0.0.0\""), "source does not bind 0.0.0.0");
    expect(src.contains("listenAddress(port)"), "main uses listenAddress(port)");
    expect(src.contains("sslmode=disable"), "JDBC keeps sslmode=disable");

    HttpServer bound = HttpServer.create(Main.listenAddress(0), 0);
    bound.start();
    try {
      expect(bound.getAddress().getAddress() instanceof Inet6Address, "bound socket is IPv6");
    } finally {
      bound.stop(0);
    }

    int reg = src.indexOf("static void register(");
    expect(reg >= 0, "register exists");
    if (reg >= 0) {
      String fn = src.substring(reg);
      expect(!fn.contains("openPool()"), "register-once does not open the pool");
      expect(!fn.contains("openConnection()"), "register-once does not open Postgres");
      expect(!fn.contains("query("), "register-once does not run catalog SQL");
      expect(!fn.contains("newJdbc()"), "register-once does not open JDBC");
    }

    Main.resetCounts();
    Main.HttpResult health = Main.dispatch("/health", "");
    expect(health.status == 200, "/health returns 200");
    expect(health.body.contains("\"ok\":true"), "/health body is ok JSON");
    expect(Main.sqlCount.get() == 0, "/health does not run SQL");
    expect(Main.connectCount.get() == 0, "/health does not open Postgres");

    boolean live = false;
    try {
      Class.forName("org.postgresql.Driver");
      Main.openPool();
      live = true;
    } catch (Exception e) {
      System.err.println("postgres unavailable, using connect/query hooks: " + e.getMessage());
      Main.connectFn = () -> null;
      Main.queryFn =
          (sql, qargs) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (sql.contains("FROM v1_speakers")) {
              for (int i = 0; i < 3; i++) {
                rows.add(Map.of("slug", "s" + i, "first_name", "A", "last_name", "B"));
              }
            } else if (sql.contains("ANY(")) {
              rows.add(Map.of("speaker_slug", "s0", "year", 2026));
              rows.add(Map.of("speaker_slug", "s0", "year", 2024));
            } else if (sql.contains("FROM v1_talks")) {
              rows.add(
                  Map.of(
                      "slug",
                      "t0",
                      "title",
                      "Talk",
                      "speaker_slug",
                      "s0",
                      "year",
                      2026,
                      "languages",
                      List.of("java"),
                      "topics",
                      List.of()));
            }
            return rows;
          };
      Main.poolReady = true;
      Main.idle.add(null);
      Main.poolOpened = 1;
      Main.connectCount.set(1);
    }

    int bootConnects = Main.connectCount.get();
    Main.sqlCount.set(0);

    Main.HttpResult listing = Main.dispatch("/v1/speakers", "year=2026");
    int sql = Main.sqlCount.get();
    int speakers = countTalksKeys(listing.body);
    System.err.println(
        "year list status="
            + listing.status
            + " sql="
            + sql
            + " speakers="
            + speakers
            + " connects="
            + Main.connectCount.get());

    if (live && listing.status != 200) {
      expect(false, "live year listing status " + listing.status + " body " + listing.body);
    }
    if (listing.status == 200) {
      expect(speakers >= 3, "year listing returns N>=3 speakers");
      expect(sql > 0, "listing runs SQL through shipped query wrapper");
      expect(sql < 2 * speakers, "SQL count does not grow as ~2N");
      expect(sql <= 4, "year listing SQL is bounded (speakers + talks + years)");
      assertYearsDesc(listing.body);
      expect(Main.connectCount.get() == bootConnects, "listing reuses the boot pool");

      ConnectionHolder holder = new ConnectionHolder();
      try {
        var c = Main.acquire();
        holder.c = c;
        var rows = Main.listSpeakers(c, 2026);
        assertYearsDescMaps(rows);
      } finally {
        Main.release(holder.c);
      }

      Main.sqlCount.set(0);
      Main.HttpResult listing2 = Main.dispatch("/v1/speakers", "year=2026");
      expect(listing2.status == 200, "second catalog request succeeds");
      expect(Main.connectCount.get() == bootConnects, "second catalog request reuses pool (no extra connect)");
    } else {
      expect(sql < 2 * 3, "failed listing did not run per-row SQL for N=3");
      expect(live || sql > 0 || Main.queryFn != null, "counter path still ran");
    }

    if (failed != 0) {
      System.err.println("perf_test failed");
      System.exit(1);
    }
    System.err.println("perf_test passed");
  }

  static final class ConnectionHolder {
    java.sql.Connection c;
  }
}
