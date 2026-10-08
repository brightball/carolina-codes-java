import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class PerfTest {
  static int failed;
  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  static final String PREVIOUS_JAVA_OPTS =
      "-XX:MaxRAMPercentage=55.0 -XX:+UseG1GC -XX:ActiveProcessorCount=1"
          + " -XX:+ExitOnOutOfMemoryError";

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
    assertDocumentedContract();
    assertGiteaWorkflowGraph();
    assertChecksumsStayOffStdout(Path.of("scripts/java-home.sh"));
    assertChecksumsStayOffStdout(Path.of("scripts/tools.sh"));

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
    assertMainBindsBeforeJdbc(src);
    assertViewsOnly(src);
    String javaOpts = productionJavaOpts();
    assertCursorPins();
    assertScannersDiscoverSources();

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

    Main.connectFn = PerfTest::stubConnection;
    Main.queryFn = PerfTest::fixture;
    Main.resetCounts();
    HttpServer server = Main.startServer(0);
    try {
      expect(server.getAddress().getAddress() instanceof Inet6Address, "server socket is IPv6");
      exerciseRoutes(server.getAddress().getPort());
    } finally {
      server.stop(0);
    }
    assertAotCacheLoads(javaOpts);
    coldStarts(javaOpts);

    if (failed != 0) {
      System.err.println("perf_test failed");
      System.exit(1);
    }
    System.err.println("perf_test passed");
  }

  static void assertDocumentedContract() throws Exception {
    String readme = Files.readString(Path.of("README.md"));
    String agents = Files.readString(Path.of("AGENTS.md"));
    String decisions = Files.readString(Path.of("DECISIONS.md"));
    String memory = Files.readString(Path.of("MEMORY.md"));
    expect(readme.contains("OpenJDK 27"), "README states OpenJDK 27");
    expect(readme.contains("com.sun.net.httpserver"), "README states com.sun.net.httpserver");
    expect(readme.contains("42.7.13"), "README states PostgreSQL JDBC 42.7.13");
    expect(readme.contains("jlink"), "README states the jlink runtime");
    expect(readme.contains("AOT cache"), "README states the JDK AOT cache");
    expect(
        readme.contains("CRaC is not a dependency"),
        "README does not describe CRaC as a dependency");
    expect(agents.contains("Do not query Ash tables"), "AGENTS.md forbids Ash tables");
    for (String view :
        List.of(
            "v1_speakers",
            "v1_sponsors",
            "v1_years",
            "v1_talks",
            "v1_sponsorships",
            "v1_year_speakers",
            "v1_year_sponsors")) {
      expect(agents.contains(view), "AGENTS.md names v1 view " + view);
    }
    expect(agents.contains("DECISIONS.md"), "AGENTS.md points at DECISIONS.md");
    expect(agents.contains("MEMORY.md"), "AGENTS.md points at MEMORY.md");
    expect(decisions.contains("## Use the JDK HTTP server"), "DECISIONS.md records httpserver");
    expect(decisions.contains("Status: Accepted"), "DECISIONS.md has an accepted record");
    expect(memory.contains("OpenJDK 27"), "MEMORY.md pins OpenJDK 27");
    expect(memory.contains("PATH"), "MEMORY.md records the PATH Java pitfall");
  }

  static void assertMainBindsBeforeJdbc(String src) {
    int mainAt = src.indexOf("public static void main");
    int next = src.indexOf("static HttpServer startServer", mainAt);
    expect(mainAt >= 0 && next > mainAt, "main is followed by startServer");
    if (mainAt < 0 || next <= mainAt) {
      return;
    }
    String body = src.substring(mainAt, next);
    expect(!body.contains("openPool("), "main does not open the pool");
    expect(!body.contains("Class.forName"), "main does not load the JDBC driver");
    expect(!body.contains("newJdbc("), "main does not open JDBC");
    expect(body.contains("startServer(port)"), "main binds before catalog work");
  }

  static void assertViewsOnly(String src) {
    Matcher tables = Pattern.compile("\\bFROM\\s+(\\w+)").matcher(src);
    boolean saw = false;
    while (tables.find()) {
      saw = true;
      expect(tables.group(1).startsWith("v1_"), "shipped query uses v1_* view " + tables.group(1));
    }
    expect(saw, "shipped SQL has FROM clauses");
  }

  static String productionJavaOpts() throws Exception {
    String fly = Files.readString(Path.of("fly.toml"));
    String docker = Files.readString(Path.of("Dockerfile"));
    Matcher m = Pattern.compile("(?m)^\\s*JAVA_OPTS\\s*=\\s*\"([^\"]*)\"").matcher(fly);
    String opts = m.find() ? m.group(1) : "";
    expect(!opts.isEmpty(), "fly.toml sets JAVA_OPTS");
    expect(docker.contains("ENV JAVA_OPTS=\"" + opts + "\""), "image JAVA_OPTS matches fly.toml");
    expect(docker.contains("exec java $JAVA_OPTS"), "image exec uses JAVA_OPTS");
    expect(
        !opts.equals(PREVIOUS_JAVA_OPTS), "JAVA_OPTS adds startup flags beyond the previous set");
    expect(opts.contains("-Xmx256m"), "heap is fixed at 256m");
    expect(opts.contains("-XX:+UseCompressedOops"), "compressed oops stays enabled");
    expect(opts.contains("-XX:+UseCompactObjectHeaders"), "compact object headers stay enabled");
    expect(!opts.contains("MaxRAMPercentage"), "heap is not a percentage of detected RAM");
    expect(opts.contains("-XX:+ExitOnOutOfMemoryError"), "OOM still exits the process");
    expect(opts.contains("-XX:+UseSerialGC"), "startup GC is serial");
    expect(opts.contains("-XX:TieredStopAtLevel=1"), "startup compilation stops at C1");
    expect(docker.contains("jlink"), "image builds a reduced runtime");
    expect(docker.contains("--output /opt/java-rt"), "jlink output replaces the full JDK tree");
    expect(
        docker.contains("COPY --from=runtime /opt/java-rt /opt/java"),
        "final image runs the jlink runtime");
    expect(
        docker.contains("-XX:AOTCache=/app/app.aot"),
        "exec uses the AOT cache built into the image");
    expect(docker.contains("-XX:AOTCacheOutput=/app/app.aot"), "image build writes the AOT cache");
    expect(
        docker.contains("-cp /app/app.jar:/app/lib/postgresql-42.7.13.jar Main"),
        "image classpath is a jar so the AOT cache can be created");
    expect(
        docker.contains("-Dcarolina.aot.train=true"), "AOT training does not need a live database");
    expect(fly.contains("app = \"carolina-codes-java\""), "fly app is carolina-codes-java");
    expect(fly.contains("internal_port = 8080"), "fly HTTP check uses internal port 8080");
    expect(fly.contains("path = \"/health\""), "fly HTTP check is GET /health");
    expect(fly.contains("method = \"GET\""), "fly check method is GET");
    return opts;
  }

  static void assertCursorPins() throws Exception {
    String env = Files.readString(Path.of(".cursor/environment.json"));
    expect(env.contains("postgresql-42.7.13.jar"), "cursor install fetches JDBC 42.7.13");
    expect(
        env.contains("org/postgresql/postgresql/42.7.13/"),
        "cursor install uses 42.7.13 coordinates");
    expect(!env.contains("42.7.7"), "cursor install is not pinned to JDBC 42.7.7");
    String docker = Files.readString(Path.of(".cursor/Dockerfile"));
    expect(docker.contains("openjdk-27"), "cursor image installs JDK 27");
    expect(!docker.contains("temurin:26"), "cursor image is not Temurin 26");
  }

  static void assertScannersDiscoverSources() throws Exception {
    String sast = Files.readString(Path.of("scripts/sast.sh"));
    String style = Files.readString(Path.of("scripts/style.sh"));
    String tools = Files.readString(Path.of("scripts/tools.sh"));
    expect(tools.contains("-name '*.java'"), "source discovery matches every .java file");
    expect(sast.contains("app_java_sources"), "sast uses discovered sources");
    expect(style.contains("app_java_sources"), "style uses discovered sources");
    expect(sast.contains("\"${pmd_args[@]}\""), "sast passes every discovered file to PMD");
    expect(
        style.contains("\"${sources[@]}\""), "style passes every discovered file to the formatter");
    expect(!sast.contains("-d Main.java"), "sast is not a stale Main.java list");
    Process proc =
        new ProcessBuilder("bash", "-c", "source scripts/tools.sh && app_java_sources")
            .directory(Path.of("").toAbsolutePath().toFile())
            .start();
    String listed = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = proc.waitFor();
    expect(exit == 0, "app_java_sources exits 0");
    List<String> found = new ArrayList<>();
    try (var walk = Files.walk(Path.of("."))) {
      walk.filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("/.tools/"))
          .forEach(p -> found.add(p.toString().replaceFirst("^\\./", "")));
    }
    java.util.Collections.sort(found);
    List<String> fromScript = new ArrayList<>();
    for (String line : listed.split("\n")) {
      if (!line.isBlank()) {
        fromScript.add(line.trim());
      }
    }
    System.err.println("application_java " + fromScript);
    expect(fromScript.equals(found), "discovered sources match the tree: " + fromScript);
    expect(fromScript.contains("Main.java"), "discovery includes Main.java");
    expect(fromScript.contains("PerfTest.java"), "discovery includes PerfTest.java");
  }

  static HttpResponse<String> get(int port, String pathAndQuery) throws Exception {
    URI uri = URI.create("http://[::1]:" + port + pathAndQuery);
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
    return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
  }

  static void exerciseRoutes(int port) throws Exception {
    HttpResponse<String> root = get(port, "/");
    HttpResponse<String> health = get(port, "/health");
    System.err.println("route GET / status=" + root.statusCode() + " body=" + root.body());
    System.err.println(
        "route GET /health status=" + health.statusCode() + " body=" + health.body());
    expect(root.statusCode() == 200, "socket GET / status");
    expect(root.body().contains("\"language\":\"Java\""), "socket GET / language");
    expect(health.statusCode() == 200, "socket GET /health status");
    expect(health.body().contains("\"ok\":true"), "socket GET /health body");
    expect(Main.connectCount.get() == 0, "socket / and /health do not open JDBC");
    expect(Main.sqlCount.get() == 0, "socket / and /health do not run SQL");

    List<Endpoint> advertised = endpoints(root.body());
    for (String path :
        List.of(
            "/",
            "/health",
            "/v1/years",
            "/v1/speakers",
            "/v1/speakers/:slug",
            "/v1/speakers/:year/:slug",
            "/v1/sponsors",
            "/v1/sponsors/:slug",
            "/v1/sponsors/:year/:slug")) {
      expect(
          advertised.stream().anyMatch(ep -> ep.path.equals(path)), "identity advertises " + path);
    }

    Main.sqlCount.set(0);
    int connectsBefore = Main.connectCount.get();
    HttpResponse<String> listing = get(port, "/v1/speakers?year=2026");
    int sql = Main.sqlCount.get();
    int speakers = countTalksKeys(listing.body());
    System.err.println(
        "route GET /v1/speakers?year=2026 status="
            + listing.statusCode()
            + " sql="
            + sql
            + " speakers="
            + speakers
            + " connects="
            + Main.connectCount.get()
            + " body="
            + listing.body());
    expect(listing.statusCode() == 200, "year listing status");
    expect(listing.body().startsWith("{\"data\":"), "year listing data envelope");
    expect(speakers >= 3, "year listing returns N>=3 speakers");
    expect(sql > 0, "listing runs SQL through shipped query wrapper");
    expect(sql < 2 * speakers, "SQL count does not grow as ~2N");
    expect(sql <= 4, "year listing SQL is bounded (speakers + talks + years)");
    assertYearsDesc(listing.body());
    expect(Main.connectCount.get() == connectsBefore + 1, "listing opens the pool once");
    int bootConnects = Main.connectCount.get();

    Main.sqlCount.set(0);
    HttpResponse<String> listing2 = get(port, "/v1/speakers?year=2026");
    System.err.println(
        "route GET /v1/speakers?year=2026 second status="
            + listing2.statusCode()
            + " connects="
            + Main.connectCount.get());
    expect(listing2.statusCode() == 200, "second catalog request succeeds");
    expect(
        Main.connectCount.get() == bootConnects,
        "second catalog request reuses pool (no extra connect)");

    for (Endpoint ep : advertised) {
      if ("/".equals(ep.path) || "/health".equals(ep.path)) {
        continue;
      }
      String path = concrete(ep.path);
      hitOk(port, path);
      if (ep.yearQuery) {
        hitOk(port, path + "?year=2026");
      }
    }

    hitMissing(port, "/v1/speakers/missing");
    hitMissing(port, "/v1/speakers/1999/ada");
    hitMissing(port, "/v1/sponsors/missing");
    hitMissing(port, "/v1/sponsors/1999/acme");
    int sqlUnknown = Main.sqlCount.get();
    HttpResponse<String> unknown = get(port, "/not-a-route");
    System.err.println(
        "route GET /not-a-route status=" + unknown.statusCode() + " body=" + unknown.body());
    expect(unknown.statusCode() == 404, "unknown path status");
    expect(unknown.body().contains("not_found"), "unknown path not_found");
    expect(Main.sqlCount.get() == sqlUnknown, "unknown path runs no SQL");
    expect(Main.connectCount.get() == bootConnects, "routes reuse the pool");

    ConnectionHolder holder = new ConnectionHolder();
    try {
      Connection c = Main.acquire();
      holder.c = c;
      assertYearsDescMaps(Main.listSpeakers(c, 2026));
    } finally {
      Main.release(holder.c);
    }
    expect(Main.connectCount.get() == bootConnects, "listSpeakers reuses the pool");

    int connects = Main.connectCount.get();
    int queries = Main.sqlCount.get();
    Main.register(port);
    expect(Main.connectCount.get() == connects, "register does not open Postgres");
    expect(Main.sqlCount.get() == queries, "register does not run catalog SQL");
  }

  static void hitOk(int port, String path) throws Exception {
    HttpResponse<String> res = get(port, path);
    System.err.println("route GET " + path + " status=" + res.statusCode() + " body=" + res.body());
    expect(res.statusCode() == 200, path + " status 200");
    expect(res.body().startsWith("{\"data\":"), path + " data envelope");
  }

  static void hitMissing(int port, String path) throws Exception {
    HttpResponse<String> res = get(port, path);
    System.err.println("route GET " + path + " status=" + res.statusCode() + " body=" + res.body());
    expect(res.statusCode() == 404, path + " status 404");
    expect(res.body().contains("not_found"), path + " not_found");
  }

  static List<Endpoint> endpoints(String body) {
    List<Endpoint> out = new ArrayList<>();
    Matcher m = Pattern.compile("\"path\":\"([^\"]+)\",\"query\":\\[([^\\]]*)]").matcher(body);
    while (m.find()) {
      out.add(new Endpoint(m.group(1), m.group(2).contains("year")));
    }
    return out;
  }

  static String concrete(String template) {
    String slug = template.contains("/sponsors/") ? "acme" : "ada";
    return template.replace(":year", "2026").replace(":slug", slug);
  }

  static boolean isMainClass(Path path) {
    String name = path.getFileName().toString();
    return name.equals("Main.class") || (name.startsWith("Main$") && name.endsWith(".class"));
  }

  static String javaBin(String tool) {
    return Path.of(System.getProperty("java.home"), "bin", tool).toString();
  }

  static List<String> javaCommand(String opts) {
    List<String> cmd = new ArrayList<>();
    cmd.add(javaBin("java"));
    for (String flag : opts.split(" ")) {
      if (!flag.isEmpty()) {
        cmd.add(flag);
      }
    }
    return cmd;
  }

  static void deleteTree(Path dir) throws Exception {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  static String readAvailable(InputStream in, ByteArrayOutputStream buf) throws Exception {
    byte[] b = new byte[4096];
    while (in.available() > 0) {
      int n = in.read(b);
      if (n < 0) {
        break;
      }
      buf.write(b, 0, n);
    }
    return buf.toString(StandardCharsets.UTF_8);
  }

  // Train an AOT cache with the production flags, then load it in a second JVM.
  // The classpath is a jar: cache creation rejects a directory.
  static String trainAndLoadAot(Path dir, Path jdbc, String opts) throws Exception {
    Path app = dir.resolve("app.jar");
    Path cache = dir.resolve("app.aot");
    List<String> jarCmd = new ArrayList<>();
    jarCmd.add(javaBin("jar"));
    jarCmd.add("cf");
    jarCmd.add(app.toString());
    try (Stream<Path> classes = Files.list(Path.of("."))) {
      for (Path classFile : classes.filter(PerfTest::isMainClass).sorted().toList()) {
        jarCmd.add(classFile.getFileName().toString());
      }
    }
    expect(jarCmd.size() > 3, "AOT jar includes Main classes");
    ProcessBuilder jarPb = new ProcessBuilder(jarCmd);
    jarPb.redirectErrorStream(true);
    Process jarProc = jarPb.start();
    String jarOut = new String(jarProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    boolean jarDone = jarProc.waitFor(30, TimeUnit.SECONDS);
    int jarExit = jarDone ? jarProc.exitValue() : -1;
    expect(jarDone && jarExit == 0, "jar app for AOT");
    if (!jarDone || jarExit != 0) {
      System.err.println(jarOut);
      return jarOut;
    }

    String cp = app.toAbsolutePath() + System.getProperty("path.separator") + jdbc.toAbsolutePath();
    List<String> train = javaCommand(opts);
    train.add("-XX:AOTCacheOutput=" + cache.toAbsolutePath());
    train.add("-Dcarolina.aot.train=true");
    train.add("-cp");
    train.add(cp);
    train.add("Main");
    ProcessBuilder trainPb = new ProcessBuilder(train);
    trainPb.redirectErrorStream(true);
    trainPb.environment().remove("JAVA_TOOL_OPTIONS");
    trainPb.environment().remove("JDK_JAVA_OPTIONS");
    Process trainProc = trainPb.start();
    String trainOut = new String(trainProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    boolean trainDone = trainProc.waitFor(60, TimeUnit.SECONDS);
    if (!trainDone) {
      trainProc.destroyForcibly();
      trainProc.waitFor(2, TimeUnit.SECONDS);
    }
    int trainExit = trainDone ? trainProc.exitValue() : -1;
    expect(trainDone && trainExit == 0, "AOT training exits 0");
    expect(Files.isRegularFile(cache) && Files.size(cache) > 0, "AOT training writes a cache");
    if (!trainDone || trainExit != 0) {
      System.err.println(trainOut);
      return trainOut;
    }

    int port;
    try (ServerSocket ss = new ServerSocket(0)) {
      port = ss.getLocalPort();
    }
    List<String> load = javaCommand(opts);
    load.add("-XX:AOTCache=" + cache.toAbsolutePath());
    load.add("-Xlog:aot=info");
    load.add("-cp");
    load.add(cp);
    load.add("Main");
    ProcessBuilder loadPb = new ProcessBuilder(load);
    loadPb.redirectErrorStream(true);
    loadPb.environment().remove("JAVA_TOOL_OPTIONS");
    loadPb.environment().remove("JDK_JAVA_OPTIONS");
    loadPb.environment().put("PORT", Integer.toString(port));
    loadPb
        .environment()
        .put("DATABASE_URL", "postgres://postgres:postgres@127.0.0.1:1/carolina_dev");
    loadPb.environment().put("CAROLINA_URL", "");
    loadPb.environment().put("POLYGLOT_REGISTER_TOKEN", "");
    Process loadProc = loadPb.start();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    String log = "";
    try {
      while (System.nanoTime() < end) {
        log = readAvailable(loadProc.getInputStream(), buf);
        if (log.contains("listening on") && log.contains("Opened AOT cache")) {
          Thread.sleep(50);
          log = readAvailable(loadProc.getInputStream(), buf);
          break;
        }
        if (!loadProc.isAlive()) {
          log = readAvailable(loadProc.getInputStream(), buf);
          break;
        }
        Thread.sleep(20);
      }
    } finally {
      loadProc.destroy();
      if (!loadProc.waitFor(2, TimeUnit.SECONDS)) {
        loadProc.destroyForcibly();
        loadProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
    return log;
  }

  static void assertAotCacheLoads(String opts) throws Exception {
    Path jdbc = Path.of("lib/postgresql-42.7.13.jar");
    expect(Files.isRegularFile(jdbc), "JDBC jar is present for AOT training");
    for (int n = 1; n <= 2; n++) {
      Path dir = Files.createTempDirectory("carolina-aot-");
      try {
        String log = trainAndLoadAot(dir, jdbc, opts);
        expect(log.contains("Opened AOT cache"), "AOT load " + n + " opened the cache");
        expect(
            log.contains("Using AOT-linked classes: true"),
            "AOT load " + n + " linked classes from the cache");
        expect(!log.contains("Unable to use AOT cache"), "AOT load " + n + " kept the cache");
        expect(
            !log.contains("saved state of UseCompressedOops"),
            "AOT load " + n + " compressed oops state matches");
        expect(
            !log.contains("Unable to map shared spaces"),
            "AOT load " + n + " mapped shared spaces");
        expect(
            log.contains("UseCompressedOops = 1"),
            "AOT load " + n + " trained with compressed oops");
      } finally {
        deleteTree(dir);
      }
    }
  }

  static void coldStarts(String opts) throws Exception {
    System.err.println("cold_start java_opts=" + opts);
    coldStart(opts, 1);
    coldStart(opts, 2);
  }

  static void coldStart(String opts, int n) throws Exception {
    int port;
    try (ServerSocket ss = new ServerSocket(0)) {
      port = ss.getLocalPort();
    }
    List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    for (String flag : opts.split(" ")) {
      if (!flag.isEmpty()) {
        cmd.add(flag);
      }
    }
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add("Main");
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(Path.of("").toAbsolutePath().toFile());
    pb.environment().put("DATABASE_URL", "postgres://postgres:postgres@127.0.0.1:1/carolina_dev");
    pb.environment().put("PORT", Integer.toString(port));
    pb.environment().put("CAROLINA_URL", "");
    pb.environment().put("POLYGLOT_REGISTER_TOKEN", "");
    pb.environment().remove("JAVA_TOOL_OPTIONS");
    pb.environment().remove("JDK_JAVA_OPTIONS");
    pb.redirectErrorStream(true);
    long start = System.nanoTime();
    Process proc = pb.start();
    StringBuilder childLog = new StringBuilder();
    Thread drain =
        Thread.startVirtualThread(
            () -> {
              try {
                childLog.append(
                    new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
              } catch (Exception ignored) {
              }
            });
    try {
      Got health = poll(proc, port, "/health", start);
      long healthMs = msSince(start);
      Got root = poll(proc, port, "/", start);
      long rootMs = msSince(start);
      System.err.println(
          "cold_start_"
              + n
              + " health_ms="
              + healthMs
              + " root_ms="
              + rootMs
              + " health="
              + health.body
              + " root="
              + root.body);
      expect(health.status == 200, "cold start " + n + " /health status");
      expect(health.body.contains("\"ok\":true"), "cold start " + n + " /health body");
      expect(root.status == 200, "cold start " + n + " / status");
      expect(root.body.contains("\"language\":\"Java\""), "cold start " + n + " identity");
      expect(healthMs < 3000, "cold start " + n + " /health within 3s (" + healthMs + "ms)");
      expect(rootMs < 3000, "cold start " + n + " / within 3s (" + rootMs + "ms)");
      expect(proc.isAlive(), "cold start " + n + " still running after identity");
      HttpResponse<String> catalog = get(port, "/v1/years");
      expect(
          catalog.statusCode() == 500, "cold start " + n + " catalog reports the refused connect");
      expect(
          catalog.body().contains("refused") || catalog.body().contains("127.0.0.1"),
          "cold start " + n + " catalog error is the unreachable database");
      expect(proc.isAlive(), "cold start " + n + " still running after refused connect");
      HttpResponse<String> again = get(port, "/health");
      expect(
          again.statusCode() == 200 && again.body().contains("\"ok\":true"),
          "cold start " + n + " /health after refused connect");
      System.err.println(
          "cold_start_"
              + n
              + " after_refused status="
              + again.statusCode()
              + " catalog="
              + catalog.body());
    } finally {
      proc.destroy();
      if (!proc.waitFor(2, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        proc.waitFor(2, TimeUnit.SECONDS);
      }
      drain.join(2000);
      if (!childLog.isEmpty()) {
        System.err.println("cold_start_" + n + " child: " + childLog.toString().trim());
      }
    }
  }

  static Got poll(Process proc, int port, String path, long start) throws Exception {
    Got got = new Got();
    while (msSince(start) < 3000) {
      if (!proc.isAlive()) {
        got.body = "process exited";
        return got;
      }
      try {
        HttpResponse<String> res = get(port, path);
        got.status = res.statusCode();
        got.body = res.body();
        if (got.status == 200) {
          return got;
        }
      } catch (Exception e) {
        Thread.sleep(15);
      }
    }
    return got;
  }

  static long msSince(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  static List<Map<String, Object>> fixture(String sql, Object[] args) {
    if (args == null) {
      args = new Object[0];
    }
    Matcher from = Pattern.compile("(?i)\\bFROM\\s+(\\w+)").matcher(sql);
    boolean saw = false;
    while (from.find()) {
      saw = true;
      expect(from.group(1).startsWith("v1_"), "SQL stays on v1_* views: " + from.group(1));
    }
    expect(saw, "SQL names a view: " + sql);
    if (sql.contains("FROM v1_years")) {
      return List.of(
          row("year", 2026, "slug", "2026", "name", "2026", "status", "announced"),
          row("year", 2024, "slug", "2024", "name", "2024", "status", "past"));
    }
    if (sql.contains("FROM v1_speakers")) {
      if (sql.contains("WHERE slug = ?")) {
        String slug = argString(args, 0);
        if ("ada".equals(slug) || "grace".equals(slug) || "linus".equals(slug)) {
          return List.of(speaker(slug));
        }
        return List.of();
      }
      return List.of(speaker("ada"), speaker("grace"), speaker("linus"));
    }
    if (sql.contains("FROM v1_year_sponsors")) {
      if (sql.contains("AND slug = ?")) {
        int year = argInt(args, 0);
        String slug = argString(args, 1);
        if ("acme".equals(slug) && year == 2026) {
          return List.of(sponsorYear(year));
        }
        return List.of();
      }
      int year = argInt(args, 0);
      if (year == 2026) {
        return List.of(sponsorYear(year));
      }
      return List.of();
    }
    if (sql.contains("FROM v1_sponsorships")) {
      if (args.length == 0 || !"acme".equals(argString(args, 0))) {
        return List.of();
      }
      if (sql.contains("SELECT DISTINCT year")) {
        return List.of(row("year", 2026), row("year", 2024));
      }
      return List.of(
          row("sponsor_slug", "acme", "year", 2026, "tier", "gold"),
          row("sponsor_slug", "acme", "year", 2024, "tier", "gold"));
    }
    if (sql.contains("FROM v1_sponsors")) {
      if (sql.contains("WHERE slug = ?")) {
        if ("acme".equals(argString(args, 0))) {
          return List.of(sponsor());
        }
        return List.of();
      }
      return List.of(sponsor());
    }
    if (sql.contains("FROM v1_talks")) {
      if (sql.contains("ANY(")) {
        return List.of(
            row("speaker_slug", "ada", "year", 2026),
            row("speaker_slug", "ada", "year", 2024),
            row("speaker_slug", "grace", "year", 2026),
            row("speaker_slug", "linus", "year", 2025));
      }
      if (sql.contains("AND year = ?")) {
        String slug = argString(args, 0);
        int year = argInt(args, 1);
        if ("ada".equals(slug) && year == 2026) {
          return List.of(talk(slug, year));
        }
        return List.of();
      }
      if (sql.contains("SELECT DISTINCT year")) {
        String slug = argString(args, 0);
        if ("ada".equals(slug)) {
          return List.of(row("year", 2026), row("year", 2024));
        }
        if ("grace".equals(slug)) {
          return List.of(row("year", 2026));
        }
        if ("linus".equals(slug)) {
          return List.of(row("year", 2025));
        }
        return List.of();
      }
      if (sql.contains("WHERE year = ?")) {
        int year = argInt(args, 0);
        if (year == 2026) {
          return List.of(talk("ada", 2026), talk("grace", 2026));
        }
        return List.of();
      }
      if (sql.contains("speaker_slug = ?")) {
        String slug = argString(args, 0);
        if ("ada".equals(slug)) {
          return List.of(talk("ada", 2026), talk("ada", 2024));
        }
        if ("grace".equals(slug)) {
          return List.of(talk("grace", 2026));
        }
        if ("linus".equals(slug)) {
          return List.of(talk("linus", 2025));
        }
        return List.of();
      }
    }
    expect(false, "fixture has no rows for SQL: " + sql);
    return List.of();
  }

  static String argString(Object[] args, int index) {
    return String.valueOf(args[index]);
  }

  static int argInt(Object[] args, int index) {
    Object value = args[index];
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  static Map<String, Object> speaker(String slug) {
    return row(
        "slug", slug, "first_name", slug, "last_name", "Speaker", "name", slug, "featured", false);
  }

  static Map<String, Object> talk(String slug, int year) {
    return row(
        "slug",
        "t-" + slug + "-" + year,
        "title",
        "Talk " + slug,
        "speaker_slug",
        slug,
        "year",
        year,
        "languages",
        List.of("java"),
        "topics",
        List.of("jvm"));
  }

  static Map<String, Object> sponsor() {
    return row("slug", "acme", "name", "Acme", "website", "https://acme.example");
  }

  static Map<String, Object> sponsorYear(int year) {
    return row("slug", "acme", "name", "Acme", "year", year, "tier", "gold", "featured", false);
  }

  static final class Endpoint {
    final String path;
    final boolean yearQuery;

    Endpoint(String path, boolean yearQuery) {
      this.path = path;
      this.yearQuery = yearQuery;
    }
  }

  static final class Got {
    int status;
    String body = "";
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
      if ("gitleaks".equals(name)) {
        expect(
            body.contains("unzip git"),
            "gitleaks restore installs git so detect can read the packed .git");
      }
    }
  }

  static void assertChecksumsStayOffStdout(Path script) throws Exception {
    expect(Files.isRegularFile(script), "shipped " + script + " exists");
    String src = Files.readString(script);
    Matcher m = Pattern.compile("(?m)^.*sha256sum -c.*$").matcher(src);
    boolean found = false;
    while (m.find()) {
      found = true;
      String line = m.group();
      expect(
          line.contains(">/dev/null") || line.contains(">&2"),
          script + " sha256sum must not write to stdout: " + line.trim());
    }
    expect(found, script + " verifies downloads with sha256sum -c");
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
