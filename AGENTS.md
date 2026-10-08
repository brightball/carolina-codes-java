# carolina-codes-java

Read-only v1 polyglot API. The process queries PostgreSQL `v1_*` views and serves JSON with `com.sun.net.httpserver`. This repository is its own git remote. The Phoenix CMS is a different remote (`github.com/brightball/carolina-codes`). Do not assume a sibling checkout exists, and do not fold this tree into the CMS remote.

The HTTP contract is the CMS `priv/api/openapi.yaml` and `priv/api/AGENTS.md`. This repo does not ship `openapi.yaml`, Compose, `db/*.sql`, `images/`, or `src/`.

Read `DECISIONS.md` before an architectural change. Append a new record, or mark the old record superseded and add the replacement. Do not silently contradict a record. When a version pin or a check command changes, update `MEMORY.md` in the same change. `MEMORY.md` is the current pins and pitfalls. `DECISIONS.md` is why.

## Contract

Query only these views: `v1_speakers`, `v1_sponsors`, `v1_years`, `v1_talks`, `v1_sponsorships`, `v1_year_speakers`, `v1_year_sponsors`.

Do not query Ash tables. Do not query base catalog tables (`speakers`, `organizations`, `talks`, and the rest). No writes. Do not implement Ash JSON:API (`application/vnd.api+json`).

Required routes:

- `GET /health` — liveness. Body is `{"ok":true}`. This route does not open Postgres.
- `GET /` — identity (`language`, `language_version`, `api_version`, `framework`, `created_year`, `schema_version`, `endpoints`)
- `GET /v1/years`
- `GET /v1/speakers` and `GET /v1/speakers?year=`
- `GET /v1/speakers/{slug}` and `GET /v1/speakers/{year}/{slug}`
- `GET /v1/sponsors` and `GET /v1/sponsors?year=`
- `GET /v1/sponsors/{slug}` and `GET /v1/sponsors/{year}/{slug}`

List payloads are `{ "data": [ ... ] }`. A single object is `{ "data": { ... } }`. Unknown slugs are HTTP 404 with `{"error":"not_found"}`. Year-scoped speaker rows include `languages` and `topics`. Year-scoped sponsor rows include `tier` and `blurb`. `photo_path` and `logo_path` are web paths. Return the path. This process does not serve image bytes.

Register once on boot. There is no heartbeat.

`POST {CAROLINA_URL}/internal/api-endpoints/register`

```
Authorization: Bearer {POLYGLOT_REGISTER_TOKEN}
Content-Type: application/json
```

Body fields: `language`, `language_version`, `api_version`, `framework`, `created_year`, `base_url` (`PUBLIC_BASE_URL`), `schema_version` (1), `endpoints`.

If `CAROLINA_URL` or `POLYGLOT_REGISTER_TOKEN` is empty, skip registration and keep serving. If the POST fails, log and keep serving.

| Variable | Example | Role |
| --- | --- | --- |
| `DATABASE_URL` | `postgres://postgres:postgres@127.0.0.1:5432/carolina_dev` | SQL views |
| `CAROLINA_URL` | `http://127.0.0.1:4000` | CMS. Registration no-ops when this is empty or the CMS is down |
| `POLYGLOT_REGISTER_TOKEN` | `dev` | Bearer token for register |
| `PUBLIC_BASE_URL` | `http://127.0.0.1:4007` | URL the CMS will call |
| `PORT` | `4007` locally, `8080` in the image | Listen port |

Handler tests use a fake catalog and do not need Postgres. Live HTTP against the views needs Postgres 16 and the variables above.

## Java

Compile, test, and scan with OpenJDK 27. `java` on `PATH` may be another release. `scripts/java-home.sh` selects JDK 27 (`$HOME/.local/jdk-27`, a mise install, or the GA tarball under `.tools/jdk-27`).

The framework is `com.sun.net.httpserver` from the JDK module `jdk.httpserver`. There is no separate framework artifact. The application dependency is the vendored driver `lib/postgresql-42.7.13.jar`.

Sources are `Main.java` and `PerfTest.java` at the repository root.

Checks are separate Make targets: `make test`, `make sast`, `make audit`, `make gitleaks`, `make style`, and `make check` for all five. Commands and tool pins are in `README.md` and `MEMORY.md`.

The listen address is `::`. `GET /health` and `GET /` run before any Postgres connection. The pool opens on the first catalog request. Registration runs on a virtual thread and does not open the pool.

The container runtime is a jlink image plus a JDK AOT cache (`-XX:AOTCache=/app/app.aot`). `JAVA_OPTS` pins the heap at `-Xmx256m` with compressed oops and compact object headers on, so training and the 512 MB machine load that cache. CRaC is not used. The image classpath is `app.jar` plus the JDBC jar, because AOT cache creation rejects a directory on the classpath. Training uses `-Dcarolina.aot.train=true` and does not need a live database.

## Decisions and memory

`DECISIONS.md` is append-only. `MEMORY.md` is edited in place when pins or commands change. Read both before changing the runtime, the SQL contract, or the check toolchain.
