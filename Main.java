import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
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
      System.getenv().getOrDefault("DATABASE_URL", "postgres://postgres:postgres@127.0.0.1:5432/carolina_dev");
  static final String SPEAKER_COLS =
      "slug, first_name, last_name, name, tagline, bio, company, location, photo_path, twitter_url, linkedin_url, website_url, github_url, featured";
  static final String YEAR_SPONSOR_COLS =
      "slug, name, website, logo_path, description, blurb, tier, featured, year, twitter_url, linkedin_url, youtube_url, instagram_url, facebook_url";
  static final String SPONSOR_COLS =
      "slug, name, website, logo_path, description, twitter_url, linkedin_url, youtube_url, instagram_url, facebook_url";
  static final String TALK_COLS =
      "slug, title, description, format, youtube_id, year, speaker_slug, languages, topics";

  public static void main(String[] args) throws Exception {
    Class.forName("org.postgresql.Driver");
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4007"));
    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
    server.createContext("/", Main::handle);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
    System.err.println("carolina-codes-java listening on :" + port);
    register(port);
  }

  static Map<String, Object> endpoint(String method, String path, List<String> query) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("method", method);
    row.put("path", path);
    row.put("query", query);
    return row;
  }

  static Connection conn() throws Exception {
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
    return DriverManager.getConnection(jdbc, user, pass);
  }

  static void handle(HttpExchange ex) {
    try {
      URI uri = ex.getRequestURI();
      String path = uri.getPath().replaceAll("/$", "");
      if (path.isEmpty()) path = "/";
      String qs = uri.getQuery() == null ? "" : uri.getQuery();
      if (path.equals("/")) {
        send(ex, 200, identity());
        return;
      }
      if (path.equals("/health")) {
        send(ex, 200, "{\"ok\":true}");
        return;
      }
      try (Connection c = conn()) { // SQLException via Exception
        if (path.equals("/v1/years")) {
          send(ex, 200, wrap(query(c, "SELECT year, slug, name, status FROM v1_years ORDER BY year DESC")));
          return;
        }
        if (path.equals("/v1/speakers")) {
          String year = param(qs, "year");
          if (year != null) {
            int y = Integer.parseInt(year);
            List<Map<String, Object>> speakers =
                query(
                    c,
                    "SELECT "
                        + SPEAKER_COLS
                        + " FROM v1_speakers WHERE slug IN (SELECT speaker_slug FROM v1_talks WHERE year = ?) ORDER BY last_name, first_name",
                    y);
            for (Map<String, Object> sp : speakers) {
              List<Map<String, Object>> talks =
                  query(c, "SELECT " + TALK_COLS + " FROM v1_talks WHERE speaker_slug = ? AND year = ?", sp.get("slug"), y);
              sp.put("year", y);
              sp.put("talks", talks);
              sp.put("languages", uniq(talks, "languages"));
              sp.put("topics", uniq(talks, "topics"));
              sp.put("years", years(c, (String) sp.get("slug")));
            }
            send(ex, 200, wrap(speakers));
            return;
          }
          send(ex, 200, wrap(query(c, "SELECT " + SPEAKER_COLS + " FROM v1_speakers ORDER BY last_name, first_name")));
          return;
        }
        Matcher ys = Pattern.compile("^/v1/speakers/(\\d{4})/([^/]+)$").matcher(path);
        if (ys.matches()) {
          int y = Integer.parseInt(ys.group(1));
          String slug = ys.group(2);
          List<Map<String, Object>> rows = query(c, "SELECT " + SPEAKER_COLS + " FROM v1_speakers WHERE slug = ?", slug);
          if (rows.isEmpty()) {
            send(ex, 404, "{\"error\":\"not_found\"}");
            return;
          }
          List<Map<String, Object>> talks =
              query(c, "SELECT " + TALK_COLS + " FROM v1_talks WHERE speaker_slug = ? AND year = ?", slug, y);
          if (talks.isEmpty()) {
            send(ex, 404, "{\"error\":\"not_found\"}");
            return;
          }
          Map<String, Object> sp = rows.get(0);
          List<Integer> yrs = years(c, slug);
          sp.put("year", y);
          sp.put("talks", talks);
          sp.put("years", yrs);
          sp.put("other_years", yrs.stream().filter(n -> n != y).toList());
          sp.put("languages", uniq(talks, "languages"));
          sp.put("topics", uniq(talks, "topics"));
          send(ex, 200, "{\"data\":" + json(sp) + "}");
          return;
        }
        Matcher s = Pattern.compile("^/v1/speakers/([^/]+)$").matcher(path);
        if (s.matches()) {
          String slug = s.group(1);
          List<Map<String, Object>> rows = query(c, "SELECT " + SPEAKER_COLS + " FROM v1_speakers WHERE slug = ?", slug);
          if (rows.isEmpty()) {
            send(ex, 404, "{\"error\":\"not_found\"}");
            return;
          }
          Map<String, Object> sp = rows.get(0);
          sp.put("talks", query(c, "SELECT " + TALK_COLS + " FROM v1_talks WHERE speaker_slug = ?", slug));
          sp.put("years", years(c, slug));
          send(ex, 200, "{\"data\":" + json(sp) + "}");
          return;
        }
        if (path.equals("/v1/sponsors")) {
          String year = param(qs, "year");
          if (year != null) {
            send(
                ex,
                200,
                wrap(
                    query(
                        c,
                        "SELECT " + YEAR_SPONSOR_COLS + " FROM v1_year_sponsors WHERE year = ? ORDER BY name",
                        Integer.parseInt(year))));
          } else {
            send(ex, 200, wrap(query(c, "SELECT " + SPONSOR_COLS + " FROM v1_sponsors ORDER BY name")));
          }
          return;
        }
        Matcher ysp = Pattern.compile("^/v1/sponsors/(\\d{4})/([^/]+)$").matcher(path);
        if (ysp.matches()) {
          int y = Integer.parseInt(ysp.group(1));
          String slug = ysp.group(2);
          List<Map<String, Object>> rows =
              query(c, "SELECT " + YEAR_SPONSOR_COLS + " FROM v1_year_sponsors WHERE year = ? AND slug = ?", y, slug);
          if (rows.isEmpty()) {
            send(ex, 404, "{\"error\":\"not_found\"}");
            return;
          }
          Map<String, Object> row = rows.get(0);
          List<Integer> yrs = sponsorYears(c, slug);
          row.put("years", yrs);
          row.put("other_years", yrs.stream().filter(n -> n != y).toList());
          send(ex, 200, "{\"data\":" + json(row) + "}");
          return;
        }
        Matcher sp = Pattern.compile("^/v1/sponsors/([^/]+)$").matcher(path);
        if (sp.matches()) {
          String slug = sp.group(1);
          List<Map<String, Object>> rows = query(c, "SELECT " + SPONSOR_COLS + " FROM v1_sponsors WHERE slug = ?", slug);
          if (rows.isEmpty()) {
            send(ex, 404, "{\"error\":\"not_found\"}");
            return;
          }
          Map<String, Object> row = rows.get(0);
          row.put("sponsorships", query(c, "SELECT * FROM v1_sponsorships WHERE sponsor_slug = ?", slug));
          send(ex, 200, "{\"data\":" + json(row) + "}");
          return;
        }
      }
      send(ex, 404, "{\"error\":\"not_found\"}");
    } catch (Exception e) {
      try {
        send(ex, 500, "{\"error\":" + quote(e.getMessage()) + "}");
      } catch (Exception ignored) {
      }
    }
  }

  static List<Integer> years(Connection c, String slug) throws SQLException {
    List<Integer> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement("SELECT DISTINCT year FROM v1_talks WHERE speaker_slug = ? ORDER BY year DESC")) {
      ps.setString(1, slug);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getInt(1));
      }
    }
    return out;
  }

  static List<Integer> sponsorYears(Connection c, String slug) throws SQLException {
    List<Integer> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement("SELECT DISTINCT year FROM v1_sponsorships WHERE sponsor_slug = ? ORDER BY year DESC")) {
      ps.setString(1, slug);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getInt(1));
      }
    }
    return out;
  }

  static List<String> uniq(List<Map<String, Object>> talks, String key) {
    LinkedHashSet<String> set = new LinkedHashSet<>();
    for (Map<String, Object> t : talks) {
      Object v = t.get(key);
      if (v instanceof List<?> list) {
        for (Object item : list) if (item != null && !item.toString().isEmpty()) set.add(item.toString());
      }
    }
    return new ArrayList<>(set);
  }

  static List<Map<String, Object>> query(Connection c, String sql, Object... args) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        Object a = args[i];
        if (a instanceof Integer iarg) ps.setInt(i + 1, iarg);
        else ps.setString(i + 1, String.valueOf(a));
      }
      try (ResultSet rs = ps.executeQuery()) {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= n; i++) {
            Object v = rs.getObject(i);
            if (v instanceof Array arr) v = Arrays.asList((Object[]) arr.getArray());
            else if (v instanceof PGobject pg && "json".equals(pg.getType())) v = pg.getValue();
            row.put(md.getColumnLabel(i), v);
          }
          rows.add(row);
        }
      }
    }
    return rows;
  }

  static String param(String qs, String name) {
    for (String part : qs.split("&")) {
      String[] kv = part.split("=", 2);
      if (kv.length == 2 && kv[0].equals(name)) return kv[1];
    }
    return null;
  }

  static String wrap(List<Map<String, Object>> rows) {
    return "{\"data\":" + json(rows) + "}";
  }

  static String json(Object o) {
    if (o == null) return "null";
    if (o instanceof Number || o instanceof Boolean) return o.toString();
    if (o instanceof String s) return quote(s);
    if (o instanceof Object[] arr) {
      return json(Arrays.asList(arr));
    }
    if (o instanceof List<?> list) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(json(list.get(i)));
      }
      return sb.append(']').toString();
    }
    if (o instanceof Map<?, ?> map) {
      StringBuilder sb = new StringBuilder("{");
      int i = 0;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (i++ > 0) sb.append(',');
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
    if (url == null || token == null || url.isBlank() || token.isBlank()) return;
    String base = Optional.ofNullable(System.getenv("PUBLIC_BASE_URL")).orElse("http://127.0.0.1:" + port);
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
    try {
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create(url.replaceAll("/$", "") + "/internal/api-endpoints/register"))
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
