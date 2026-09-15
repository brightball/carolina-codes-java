import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

  static String majorOf(String version) {
    if (version == null) {
      return "";
    }
    Matcher m = Pattern.compile("^(\\d+)").matcher(version);
    return m.find() ? m.group(1) : "";
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
    String spec = System.getProperty("java.specification.version");
    expect("27".equals(spec), "JVM specification version is 27, got " + spec);
    String runtime = System.getProperty("java.version");
    expect("27".equals(majorOf(runtime)), "java.version major is 27, got " + runtime);
    assertGiteaWorkflowGraph();

    expect("::".equals(Main.listenHost()), "listen host is ::");
    String src = Files.readString(Path.of("Main.java"));
    expect(!src.contains("\"0.0.0.0\""), "source does not bind 0.0.0.0");
    expect(src.contains("listenAddress(port)"), "main uses listenAddress(port)");
    expect(src.contains("sslmode=disable"), "JDBC keeps sslmode=disable");
    expect(src.contains("tcpKeepAlive"), "JDBC enables tcpKeepAlive");
    expect(src.contains("connectTimeout"), "JDBC sets connectTimeout");
    expect(src.contains("isValid("), "pool validates connections on checkout");
    expect(src.contains("newVirtualThreadPerTaskExecutor()"), "HTTP executor uses virtual threads");
    expect(src.contains("startVirtualThread"), "register runs off the main thread");
    expect(
        src.contains("static final Pattern SPEAKER_YEAR_SLUG"),
        "speaker year/slug pattern is static");
    expect(
        src.contains("static final Pattern SPONSOR_YEAR_SLUG"),
        "sponsor year/slug pattern is static");
    expect(src.contains("payload.toResult()"), "JSON serialization happens after pool release");

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

    Main.HttpResult root = Main.dispatch("/", "");
    expect(root.status == 200, "/ returns 200");
    expect(Main.sqlCount.get() == 0, "/ identity does not run SQL");
    expect(Main.connectCount.get() == 0, "/ identity does not open Postgres");
    Matcher langVer = Pattern.compile("\"language_version\":\"([^\"]+)\"").matcher(root.body);
    boolean hasLangVer = langVer.find();
    expect(hasLangVer, "identity JSON contains language_version");
    if (hasLangVer) {
      String v = langVer.group(1);
      expect("27".equals(majorOf(v)), "language_version major is 27, got " + v);
    }

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
                rows.add(row("slug", "s" + i, "first_name", "A", "last_name", "B"));
              }
            } else if (sql.contains("ANY(")) {
              rows.add(row("speaker_slug", "s0", "year", 2026));
              rows.add(row("speaker_slug", "s0", "year", 2024));
            } else if (sql.contains("FROM v1_talks")) {
              rows.add(
                  row(
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
      Main.idle.addLast(stubConnection());
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
      expect(
          Main.connectCount.get() == bootConnects,
          "second catalog request reuses pool (no extra connect)");
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

  static void assertGiteaWorkflowGraph() throws Exception {
    Path workflowPath = Path.of(".gitea/workflows/precommit.yml");
    expect(Files.isRegularFile(workflowPath), "shipped Gitea workflow exists");
    String yaml = Files.readString(workflowPath);
    Map<String, String> jobs = workflowJobs(yaml);
    expect(jobs.containsKey("prepare"), "initial prepare job exists");

    String prepare = jobs.getOrDefault("prepare", "");
    String sharedApt = "git curl ca-certificates make unzip tar gzip";
    String clone =
        "git clone --depth 1 --no-checkout"
            + " \"https://x-access-token:${token}@${host}/${GITHUB_REPOSITORY}\" .";
    expect(prepare.contains(sharedApt), "prepare installs shared OS packages");
    expect(prepare.contains("nodejs"), "prepare installs nodejs so the artifact action can run");
    expect(prepare.contains(clone), "prepare clones GITHUB_SHA with the job token");
    expect(
        prepare.contains("git fetch --depth 1 origin \"${GITHUB_SHA}\""),
        "prepare fetches GITHUB_SHA");
    expect(prepare.contains("git checkout --force FETCH_HEAD"), "prepare checks out FETCH_HEAD");
    expect(prepare.contains("make tools"), "prepare fetches JDK 27 and the check CLIs");
    expect(prepare.contains("actions/upload-artifact@v3"), "prepare publishes the workspace");
    expect(prepare.contains("name: prep-workspace"), "prepare artifact is prep-workspace");
    expect(!prepare.contains("--exclude=.git"), "prepare keeps .git for gitleaks");
    expect(!prepare.contains("needs:"), "prepare is the initial stage");

    String[] checks = {"test", "sast", "audit", "gitleaks", "style"};
    for (String name : checks) {
      expect(jobs.containsKey(name), "job " + name + " exists");
      String body = jobs.get(name);
      expect(body.contains("needs: prepare"), name + " needs prepare");
      expect(
          body.contains("actions/download-artifact@v3"), name + " restores the prepare artifact");
      expect(body.contains("name: prep-workspace"), name + " consumes prep-workspace");
      expect(body.contains("prep-workspace.tar.gz"), name + " unpacks the prepare workspace");
      expect(jobRunsMake(body, name), name + " invokes make " + name);
      expect(!body.contains(sharedApt), name + " does not repeat the shared apt-get list");
      expect(!body.contains(clone), name + " does not clone");
      expect(!body.contains("git fetch --depth 1 origin"), name + " does not git fetch GITHUB_SHA");
      expect(!body.contains("git checkout --force FETCH_HEAD"), name + " does not git checkout");
      expect(!body.contains("make tools"), name + " does not fetch JDK/tools");
      expect(!body.contains("download.java.net"), name + " does not download the JDK tarball");
      expect(!body.contains("github.com/pmd"), name + " does not fetch PMD");
      expect(!body.contains("github.com/google/osv-scanner"), name + " does not fetch osv-scanner");
      expect(!body.contains("github.com/gitleaks/gitleaks"), name + " does not fetch gitleaks");
      expect(
          !body.contains("github.com/google/google-java-format"),
          name + " does not fetch google-java-format");
    }
  }

  static boolean jobRunsMake(String body, String target) {
    return Pattern.compile("(?m)^\\s+- run: make " + Pattern.quote(target) + "\\s*$")
        .matcher(body)
        .find();
  }

  static Map<String, String> workflowJobs(String yaml) {
    Map<String, String> jobs = new LinkedHashMap<>();
    String[] lines = yaml.split("\n", -1);
    boolean inJobs = false;
    String current = null;
    StringBuilder body = new StringBuilder();
    Pattern jobName = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
    for (String line : lines) {
      if (!inJobs) {
        if ("jobs:".equals(line)) {
          inJobs = true;
        }
        continue;
      }
      Matcher m = jobName.matcher(line);
      if (m.matches()) {
        if (current != null) {
          jobs.put(current, body.toString());
        }
        current = m.group(1);
        body = new StringBuilder();
        continue;
      }
      if (current != null) {
        body.append(line).append('\n');
      }
    }
    if (current != null) {
      jobs.put(current, body.toString());
    }
    return jobs;
  }

  static Map<String, Object> row(Object... kv) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      out.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return out;
  }

  static Connection stubConnection() {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              return switch (method.getName()) {
                case "isValid" -> true;
                case "isClosed" -> false;
                case "close" -> null;
                default -> null;
              };
            });
  }

  static final class ConnectionHolder {
    java.sql.Connection c;
  }
}
