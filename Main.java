import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.util.PGobject;

public class Main {
  static final String LANGUAGE = "Java";
  static final String FRAMEWORK = "com.sun.net.httpserver";
  static final String API_VERSION = "0.2.0";
  static final int CREATED_YEAR = 2026;
  static final int SCHEMA_VERSION = 1;
  static final String LANGUAGE_VERSION = System.getProperty("java.version");
  static final List<Map<String, Object>> ENDPOINTS =
      List.of(
          endpoint("GET", "/", List.of()),
          endpoint("GET", "/health", List.of()),
          endpoint("GET", "/v1/years", List.of()),
          endpoint("GET", "/v1/speakers", List.of("year")),
          endpoint("GET", "/v1/speakers/:slug", List.of()),
          endpoint("GET", "/v1/speakers/:year/:slug", List.of()),
          endpoint("GET", "/v1/sponsors", List.of("year")),
          endpoint("GET", "/v1/sponsors/:slug", List.of()),
          endpoint("GET", "/v1/sponsors/:year/:slug", List.of()));
  static final String DSN =
      System.getenv()
          .getOrDefault("DATABASE_URL", "postgres://postgres:postgres@127.0.0.1:5432/carolina_dev");
  static final String SPEAKER_COLS =
      "slug, first_name, last_name, name, tagline, bio, company, location, photo_path, twitter_url,"
          + " linkedin_url, website_url, github_url, featured";
  static final String YEAR_SPONSOR_COLS =
      "slug, name, website, logo_path, description, blurb, tier, featured, year, twitter_url,"
          + " linkedin_url, youtube_url, instagram_url, facebook_url";
  static final String SPONSOR_COLS =
      "slug, name, website, logo_path, description, twitter_url, linkedin_url, youtube_url,"
          + " instagram_url, facebook_url";
  static final String TALK_COLS =
      "slug, title, description, format, youtube_id, year, speaker_slug, languages, topics";

  static final Pattern SPEAKER_YEAR_SLUG = Pattern.compile("^/v1/speakers/(\\d{4})/([^/]+)$");
  static final Pattern SPEAKER_SLUG = Pattern.compile("^/v1/speakers/([^/]+)$");
  static final Pattern SPONSOR_YEAR_SLUG = Pattern.compile("^/v1/sponsors/(\\d{4})/([^/]+)$");
  static final Pattern SPONSOR_SLUG = Pattern.compile("^/v1/sponsors/([^/]+)$");

  static final int POOL_SIZE = 8;
  static final int POOL_WAIT_MS = 10_000;
  static final Object poolLock = new Object();
  static final ArrayDeque<Connection> idle = new ArrayDeque<>();
  static int poolOpened;
  static boolean poolReady;

  static final AtomicInteger sqlCount = new AtomicInteger();
  static final AtomicInteger connectCount = new AtomicInteger();

  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  interface ConnectFn {
    Connection open() throws Exception;
  }

  interface QueryFn {
    List<Map<String, Object>> query(String sql, Object[] args) throws SQLException;
  }

  static ConnectFn connectFn;
  static QueryFn queryFn;

  static final class HttpResult {
    final int status;
    final String body;

    HttpResult(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  static final class Payload {
    final int status;
    final Object data;
    final String error;

    Payload(int status, Object data, String error) {
      this.status = status;
      this.data = data;
      this.error = error;
    }

    static Payload ok(Object data) {
      return new Payload(200, data, null);
    }

    static Payload notFound() {
      return new Payload(404, null, "not_found");
    }

    HttpResult toResult() {
      if (error != null) {
        return new HttpResult(status, "{\"error\":" + quote(error) + "}");
      }
      if (data instanceof List<?> list) {
        return new HttpResult(status, "{\"data\":" + json(list) + "}");
      }
      return new HttpResult(status, "{\"data\":" + json(data) + "}");
    }
  }

  public static void main(String[] args) throws Exception {
    if (Boolean.getBoolean("carolina.aot.train")) {
      aotTrain();
      return;
    }
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4007"));
    startServer(port);
    System.err.println("carolina-codes-java listening on :" + port);
    Thread.startVirtualThread(() -> register(port));
  }

  static HttpServer startServer(int port) throws Exception {
    HttpServer server = HttpServer.create(listenAddress(port), 0);
    server.createContext("/", Main::handle);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  // Image build loads the listen path and the driver, then exits so the AOT
  // cache can be written. No live database: a refused connect is enough.
  static void aotTrain() throws Exception {
    loadDriver();
    dispatch("/health", "");
    dispatch("/", "");
    dispatch("/v1/years", "");
    HttpServer server = startServer(0);
    server.stop(0);
  }

  static String listenHost() {
    return "::";
  }

  static InetSocketAddress listenAddress(int port) throws Exception {
    return new InetSocketAddress(InetAddress.getByName(listenHost()), port);
  }

  static void resetCounts() {
    sqlCount.set(0);
    connectCount.set(0);
  }

  static Map<String, Object> endpoint(String method, String path, List<String> query) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("method", method);
    row.put("path", path);
    row.put("query", query);
    return row;
  }

  static void loadDriver() throws ClassNotFoundException {
    Class.forName("org.postgresql.Driver");
  }

  static Connection newJdbc() throws Exception {
    loadDriver();
    String raw = DSN.replace("postgres://", "http://").replace("postgresql://", "http://");
    URI u = URI.create(raw);
    String user = "postgres";
    String pass = "postgres";
    if (u.getUserInfo() != null) {
      String[] up = u.getUserInfo().split(":", 2);
      user = up[0];
      pass = up.length > 1 ? up[1] : "";
    }
    int port = u.getPort() == -1 ? 5432 : u.getPort();
    String jdbc = "jdbc:postgresql://" + u.getHost() + ":" + port + u.getPath();
    String query = u.getQuery();
    jdbc += (query == null || query.isEmpty()) ? "?sslmode=disable" : "?" + query;
    if (!jdbc.contains("sslmode=")) {
      jdbc += "&sslmode=disable";
    }
    if (!jdbc.contains("ssl=")) {
      jdbc += "&ssl=false";
    }
    jdbc = appendParam(jdbc, "tcpKeepAlive", "true");
    jdbc = appendParam(jdbc, "connectTimeout", "10");
    jdbc = appendParam(jdbc, "socketTimeout", "30");
    jdbc = appendParam(jdbc, "ApplicationName", "carolina-codes-java");
    return DriverManager.getConnection(jdbc, user, pass);
  }

  static String appendParam(String jdbc, String key, String value) {
    if (jdbc.contains(key + "=")) {
      return jdbc;
    }
    return jdbc + (jdbc.contains("?") ? "&" : "?") + key + "=" + value;
  }

  static Connection openConnection() throws Exception {
    connectCount.incrementAndGet();
    if (connectFn != null) {
      return connectFn.open();
    }
    return newJdbc();
  }

  static void openPool() throws Exception {
    synchronized (poolLock) {
      if (poolReady) {
        return;
      }
      idle.addLast(openConnection());
      poolOpened = 1;
      poolReady = true;
    }
  }

  static Connection acquire() throws Exception {
    synchronized (poolLock) {
      if (!poolReady) {
        openPool();
      }
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(POOL_WAIT_MS);
    while (true) {
      Connection c = takeIdleOrGrow();
      if (c != null) {
        if (usable(c)) {
          return c;
        }
        discardBroken(c);
        continue;
      }
      long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (remainingMs <= 0) {
        throw new SQLException("connection pool exhausted");
      }
      synchronized (poolLock) {
        if (idle.isEmpty() && poolOpened >= POOL_SIZE) {
          poolLock.wait(remainingMs);
        }
      }
    }
  }

  static Connection takeIdleOrGrow() throws Exception {
    synchronized (poolLock) {
      if (!idle.isEmpty()) {
        return idle.removeFirst();
      }
      if (poolOpened < POOL_SIZE) {
        poolOpened++;
      } else {
        return null;
      }
    }
    try {
      return openConnection();
    } catch (Exception e) {
      synchronized (poolLock) {
        poolOpened--;
        poolLock.notify();
      }
      throw e;
    }
  }

  static boolean usable(Connection c) {
    if (c == null) {
      return connectFn != null;
    }
    try {
      return !c.isClosed() && c.isValid(1);
    } catch (SQLException e) {
      return false;
    }
  }

  static void discardBroken(Connection c) {
    synchronized (poolLock) {
      poolOpened = Math.max(0, poolOpened - 1);
    }
    if (c != null) {
      try {
        c.close();
      } catch (Exception ignored) {
      }
    }
  }

  static void release(Connection c) {
    if (c == null) {
      return;
    }
    synchronized (poolLock) {
      idle.addLast(c);
      poolLock.notify();
    }
  }

  static void handle(HttpExchange ex) {
    URI uri = ex.getRequestURI();
    String path = uri.getPath();
    String qs = uri.getQuery() == null ? "" : uri.getQuery();
    HttpResult result = dispatch(path, qs);
    try {
      send(ex, result.status, result.body);
    } catch (Exception e) {
      try {
        send(ex, 500, "{\"error\":" + quote(String.valueOf(e.getMessage())) + "}");
      } catch (Exception ignored) {
      }
    }
  }

  static String trimSlash(String path) {
    if (path == null || path.isEmpty()) {
      return "/";
    }
    int end = path.length();
    while (end > 1 && path.charAt(end - 1) == '/') {
      end--;
    }
    return end == path.length() ? path : path.substring(0, end);
  }

  static HttpResult dispatch(String path, String qs) {
    try {
      path = trimSlash(path);
      if (path.isEmpty()) {
        path = "/";
      }
      if (qs == null) {
        qs = "";
      }
      if (path.equals("/")) {
        return new HttpResult(200, identity());
      }
      if (path.equals("/health")) {
        return new HttpResult(200, "{\"ok\":true}");
      }
      Payload payload;
      Connection c = acquire();
      try {
        payload = catalog(c, path, qs);
      } finally {
        release(c);
      }
      return payload.toResult();
    } catch (Exception e) {
      return new HttpResult(500, "{\"error\":" + quote(String.valueOf(e.getMessage())) + "}");
    }
  }

  static Payload catalog(Connection c, String path, String qs) throws Exception {
    if (path.equals("/v1/years")) {
      return Payload.ok(
          query(c, "SELECT year, slug, name, status FROM v1_years ORDER BY year DESC"));
    }
    if (path.equals("/v1/speakers")) {
      String year = param(qs, "year");
      Integer y = year == null ? null : Integer.parseInt(year);
      return Payload.ok(listSpeakers(c, y));
    }
    Matcher ys = SPEAKER_YEAR_SLUG.matcher(path);
    if (ys.matches()) {
      int y = Integer.parseInt(ys.group(1));
      String slug = ys.group(2);
      List<Map<String, Object>> rows =
          query(c, "SELECT " + SPEAKER_COLS + " FROM v1_speakers WHERE slug = ?", slug);
      if (rows.isEmpty()) {
        return Payload.notFound();
      }
      List<Map<String, Object>> talks =
          query(
              c,
              "SELECT " + TALK_COLS + " FROM v1_talks WHERE speaker_slug = ? AND year = ?",
              slug,
              y);
      if (talks.isEmpty()) {
        return Payload.notFound();
      }
      Map<String, Object> sp = rows.get(0);
      List<Integer> yrs = years(c, slug);
      sp.put("year", y);
      sp.put("talks", talks);
      sp.put("years", yrs);
      sp.put("other_years", yrs.stream().filter(n -> n != y).toList());
      sp.put("languages", uniq(talks, "languages"));
      sp.put("topics", uniq(talks, "topics"));
      return Payload.ok(sp);
    }
    Matcher s = SPEAKER_SLUG.matcher(path);
    if (s.matches()) {
      String slug = s.group(1);
      List<Map<String, Object>> rows =
          query(c, "SELECT " + SPEAKER_COLS + " FROM v1_speakers WHERE slug = ?", slug);
      if (rows.isEmpty()) {
        return Payload.notFound();
      }
      Map<String, Object> sp = rows.get(0);
      sp.put(
          "talks", query(c, "SELECT " + TALK_COLS + " FROM v1_talks WHERE speaker_slug = ?", slug));
      sp.put("years", years(c, slug));
      return Payload.ok(sp);
    }
    if (path.equals("/v1/sponsors")) {
      String year = param(qs, "year");
      if (year != null) {
        return Payload.ok(
            query(
                c,
                "SELECT "
                    + YEAR_SPONSOR_COLS
                    + " FROM v1_year_sponsors WHERE year = ? ORDER BY name",
                Integer.parseInt(year)));
      }
      return Payload.ok(query(c, "SELECT " + SPONSOR_COLS + " FROM v1_sponsors ORDER BY name"));
    }
    Matcher ysp = SPONSOR_YEAR_SLUG.matcher(path);
    if (ysp.matches()) {
      int y = Integer.parseInt(ysp.group(1));
      String slug = ysp.group(2);
      List<Map<String, Object>> rows =
          query(
              c,
              "SELECT " + YEAR_SPONSOR_COLS + " FROM v1_year_sponsors WHERE year = ? AND slug = ?",
              y,
              slug);
      if (rows.isEmpty()) {
        return Payload.notFound();
      }
      Map<String, Object> row = rows.get(0);
      List<Integer> yrs = sponsorYears(c, slug);
      row.put("years", yrs);
      row.put("other_years", yrs.stream().filter(n -> n != y).toList());
      return Payload.ok(row);
    }
    Matcher sp = SPONSOR_SLUG.matcher(path);
    if (sp.matches()) {
      String slug = sp.group(1);
      List<Map<String, Object>> rows =
          query(c, "SELECT " + SPONSOR_COLS + " FROM v1_sponsors WHERE slug = ?", slug);
      if (rows.isEmpty()) {
        return Payload.notFound();
      }
      Map<String, Object> row = rows.get(0);
      row.put(
          "sponsorships", query(c, "SELECT * FROM v1_sponsorships WHERE sponsor_slug = ?", slug));
      return Payload.ok(row);
    }
    return Payload.notFound();
  }

  static List<Map<String, Object>> listSpeakers(Connection c, Integer year) throws SQLException {
    String sql = "SELECT " + SPEAKER_COLS + " FROM v1_speakers";
    List<Map<String, Object>> speakers;
    if (year != null) {
      speakers =
          query(
              c,
              sql
                  + " WHERE slug IN (SELECT speaker_slug FROM v1_talks WHERE year = ?) ORDER BY"
                  + " last_name, first_name",
              year);
      return attachYearTags(c, speakers, year);
    }
    return query(c, sql + " ORDER BY last_name, first_name");
  }

  static List<Map<String, Object>> attachYearTags(
      Connection c, List<Map<String, Object>> speakers, int year) throws SQLException {
    if (speakers.isEmpty()) {
      return speakers;
    }
    Map<String, List<Map<String, Object>>> talksBy = loadTalksForYear(c, year);
    List<String> slugs = new ArrayList<>();
    for (Map<String, Object> speaker : speakers) {
      slugs.add(String.valueOf(speaker.get("slug")));
    }
    Map<String, List<Integer>> yearsBy = loadYearsForSlugs(c, slugs);
    for (Map<String, Object> speaker : speakers) {
      String slug = String.valueOf(speaker.get("slug"));
      List<Map<String, Object>> talks = talksBy.getOrDefault(slug, List.of());
      List<Integer> yrs = yearsBy.getOrDefault(slug, List.of());
      speaker.put("year", year);
      speaker.put("talks", talks);
      speaker.put("languages", uniq(talks, "languages"));
      speaker.put("topics", uniq(talks, "topics"));
      speaker.put("years", yrs);
    }
    return speakers;
  }

  static Map<String, List<Map<String, Object>>> loadTalksForYear(Connection c, int year)
      throws SQLException {
    Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
    for (Map<String, Object> talk :
        query(
            c,
            "SELECT "
                + TALK_COLS
                + " FROM v1_talks WHERE year = ? ORDER BY speaker_slug, year DESC",
            year)) {
      String slug = String.valueOf(talk.get("speaker_slug"));
      out.computeIfAbsent(slug, k -> new ArrayList<>()).add(talk);
    }
    return out;
  }

  static Map<String, List<Integer>> loadYearsForSlugs(Connection c, List<String> slugs)
      throws SQLException {
    Map<String, List<Integer>> out = new LinkedHashMap<>();
    if (slugs.isEmpty()) {
      return out;
    }
    for (Map<String, Object> row :
        query(
            c,
            "SELECT DISTINCT speaker_slug, year FROM v1_talks WHERE speaker_slug = ANY(?::text[])"
                + " ORDER BY speaker_slug, year DESC",
            (Object) slugs.toArray(new String[0]))) {
      String slug = String.valueOf(row.get("speaker_slug"));
      out.computeIfAbsent(slug, k -> new ArrayList<>()).add(asInt(row.get("year")));
    }
    return out;
  }

  static int asInt(Object y) {
    if (y instanceof Number n) {
      return n.intValue();
    }
    return Integer.parseInt(String.valueOf(y));
  }

  static List<Integer> years(Connection c, String slug) throws SQLException {
    List<Integer> out = new ArrayList<>();
    for (Map<String, Object> row :
        query(
            c,
            "SELECT DISTINCT year FROM v1_talks WHERE speaker_slug = ? ORDER BY year DESC",
            slug)) {
      out.add(asInt(row.get("year")));
    }
    return out;
  }

  static List<Integer> sponsorYears(Connection c, String slug) throws SQLException {
    List<Integer> out = new ArrayList<>();
    for (Map<String, Object> row :
        query(
            c,
            "SELECT DISTINCT year FROM v1_sponsorships WHERE sponsor_slug = ? ORDER BY year DESC",
            slug)) {
      out.add(asInt(row.get("year")));
    }
    return out;
  }

  static List<String> uniq(List<Map<String, Object>> talks, String key) {
    LinkedHashSet<String> set = new LinkedHashSet<>();
    for (Map<String, Object> t : talks) {
      Object v = t.get(key);
      if (v instanceof List<?> list) {
        for (Object item : list) {
          if (item != null && !item.toString().isEmpty()) {
            set.add(item.toString());
          }
        }
      }
    }
    return new ArrayList<>(set);
  }

  static List<Map<String, Object>> query(Connection c, String sql, Object... args)
      throws SQLException {
    sqlCount.incrementAndGet();
    if (queryFn != null) {
      return queryFn.query(sql, args);
    }
    if (c == null) {
      throw new SQLException("no connection");
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        bind(ps, c, i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
          labels[i] = md.getColumnLabel(i + 1);
        }
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 0; i < n; i++) {
            Object v = rs.getObject(i + 1);
            if (v instanceof Array arr) {
              v = Arrays.asList((Object[]) arr.getArray());
            } else if (v instanceof PGobject pg && "json".equals(pg.getType())) {
              v = pg.getValue();
            }
            row.put(labels[i], v);
          }
          rows.add(row);
        }
      }
    }
    return rows;
  }

  static void bind(PreparedStatement ps, Connection c, int idx, Object a) throws SQLException {
    if (a instanceof Integer iarg) {
      ps.setInt(idx, iarg);
    } else if (a instanceof Long larg) {
      ps.setLong(idx, larg);
    } else if (a instanceof String[] sarr) {
      ps.setArray(idx, c.createArrayOf("text", sarr));
    } else if (a instanceof List<?> list) {
      ps.setArray(idx, c.createArrayOf("text", list.toArray()));
    } else {
      ps.setString(idx, String.valueOf(a));
    }
  }

  static String param(String qs, String name) {
    for (String part : qs.split("&")) {
      String[] kv = part.split("=", 2);
      if (kv.length == 2 && kv[0].equals(name)) {
        return kv[1];
      }
    }
    return null;
  }

  static String json(Object o) {
    if (o == null) {
      return "null";
    }
    if (o instanceof Number || o instanceof Boolean) {
      return o.toString();
    }
    if (o instanceof String s) {
      return quote(s);
    }
    if (o instanceof Object[] arr) {
      return json(Arrays.asList(arr));
    }
    if (o instanceof List<?> list) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(json(list.get(i)));
      }
      return sb.append(']').toString();
    }
    if (o instanceof Map<?, ?> map) {
      StringBuilder sb = new StringBuilder("{");
      int i = 0;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (i++ > 0) {
          sb.append(',');
        }
        sb.append(quote(String.valueOf(e.getKey()))).append(':').append(json(e.getValue()));
      }
      return sb.append('}').toString();
    }
    return quote(o.toString());
  }

  static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  static String identity() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("language", LANGUAGE);
    body.put("language_version", LANGUAGE_VERSION);
    body.put("api_version", API_VERSION);
    body.put("framework", FRAMEWORK);
    body.put("created_year", CREATED_YEAR);
    body.put("schema_version", SCHEMA_VERSION);
    body.put("endpoints", ENDPOINTS);
    return json(body);
  }

  static void send(HttpExchange ex, int status, String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.getResponseHeaders().set("X-Polyglot-Language", LANGUAGE);
    ex.getResponseHeaders().set("X-Polyglot-Framework", FRAMEWORK);
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  static void register(int port) {
    String url = System.getenv("CAROLINA_URL");
    String token = System.getenv("POLYGLOT_REGISTER_TOKEN");
    if (url == null || token == null || url.isBlank() || token.isBlank()) {
      return;
    }
    String base =
        Optional.ofNullable(System.getenv("PUBLIC_BASE_URL")).orElse("http://127.0.0.1:" + port);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("language", LANGUAGE);
    payload.put("language_version", LANGUAGE_VERSION);
    payload.put("api_version", API_VERSION);
    payload.put("framework", FRAMEWORK);
    payload.put("created_year", CREATED_YEAR);
    payload.put("schema_version", SCHEMA_VERSION);
    payload.put("base_url", base);
    payload.put("endpoints", ENDPOINTS);
    String body = json(payload);
    String root = url;
    while (root.endsWith("/")) {
      root = root.substring(0, root.length() - 1);
    }
    try {
      HTTP.send(
          HttpRequest.newBuilder(URI.create(root + "/internal/api-endpoints/register"))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.discarding());
    } catch (Exception e) {
      System.err.println("register: " + e.getMessage());
    }
  }
}
