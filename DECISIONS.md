# Decisions

Append-only architecture log for this OpenJDK 27 and `com.sun.net.httpserver` API. One decision per section, in Michael Nygard's form: status, context, decision, consequences.

This tree is a single `Main.java` program with no Maven reactor, so the log stays in this file. A `docs/adr/` sequence would split the same history across files without a second package or module to separate.

When a choice changes, add a new section and set the old section's status to `Superseded by` that new heading. Leave the old context and decision text in place. `MEMORY.md` holds the current pins and commands. This file holds why. Agents read this file before an architectural change.

## Use the JDK HTTP server

- Status: Accepted
- Date: 2026-08-28

### Context

The polyglot starter tells each language to replace the sample runtime and serve the v1 JSON contract. A third-party HTTP framework would be another artifact to vendor, checksum, and scan. JDK 27 already ships the module `jdk.httpserver`.

### Decision

`Main.java` serves with `com.sun.net.httpserver.HttpServer`. `Main.FRAMEWORK` is the string `com.sun.net.httpserver`. There is no framework jar and no framework version coordinate. The JDK pin is the framework pin.

### Consequences

Routing and JSON live in `Main.dispatch` and `Payload`. HTTP work uses `Executors.newVirtualThreadPerTaskExecutor()`. The jlink image must include `jdk.httpserver`. Registration uses `java.net.http.HttpClient`, so the image also includes `java.net.http`.

## Pin OpenJDK 27

- Status: Accepted
- Date: 2026-09-15

### Context

Checks and the image must not follow whatever `java` is on `PATH`. On this fleet that command has been Java 26. Eclipse Temurin 27 was not published when the image was pinned, so the Dockerfile installs the OpenJDK 27 GA GPL tarball (build 35) from `https://jdk.java.net/27/`.

### Decision

The specification version is 27. `scripts/java-home.sh` prints a JDK 27 home, or downloads that GA tarball into `.tools/jdk-27`. `make test`, `make sast`, and `make style` compile and run through `with_jdk27`. `PerfTest` fails unless `java.specification.version` is 27.

### Consequences

Agents compile and test with the home from `scripts/java-home.sh`. Checksums for the tarball stay in `Dockerfile` and `scripts/java-home.sh`. A JDK bump updates those checksums, this log, and `MEMORY.md` together.

## jlink runtime and JDK AOT cache

- Status: Accepted for jlink and `-XX:AOTCache`. Heap sizing is superseded by "Pin the AOT heap under the compressed-oops cutoff".
- Date: 2026-09-22

### Context

The Fly image should start quickly on a 512 MB shared-cpu machine. Shipping a full JDK into the final stage is unused weight. JDK 27 can write an AOT cache with `-XX:AOTCacheOutput` and load it with `-XX:AOTCache`. CRaC is not installed or configured anywhere in this tree.

### Decision

The Dockerfile builds a jlink runtime at `/opt/java-rt` and an AOT cache at `/app/app.aot`. Training runs `Main` with `-Dcarolina.aot.train=true` and exits. The final process is `java -XX:AOTCache=/app/app.aot` on that jlink runtime. `JAVA_OPTS` in the image match `fly.toml` (`UseSerialGC`, `TieredStopAtLevel=1`, `MaxRAMPercentage=55.0`, and the other startup flags recorded there).

### Consequences

The image classpath is `/app/app.jar` plus `lib/postgresql-42.7.13.jar`. AOT cache creation rejects a directory on the classpath. Training does not need a live database; a refused connect is enough. `jlink --strip-debug` needs `objcopy`, so the build stage installs `binutils`. Adding a JDK API means adding its module to the `jlink --add-modules` line. CRaC checkpoints are not part of the build.

## Pin the AOT heap under the compressed-oops cutoff

- Status: Accepted
- Date: 2026-10-07

### Context

`JAVA_OPTS` used `-XX:MaxRAMPercentage=55.0` for both AOT training and the Fly process. That percentage follows the machine's RAM. On a builder with about 64 GB it is a 34 GB heap, and the JVM turns compressed oops off. The Fly machine has 512 MB, so the same flag leaves compressed oops on. JDK 27 then refuses the cache (`Unable to use AOT cache`, saved `UseCompressedOops` 0 against runtime 1) and every autostop pays a full cold start. JDK 27 removed `-XX:MaxRAM`, so the builder cannot be capped by pretending it has less memory.

### Decision

Training and the Fly process share one heap setting: `-Xmx256m -XX:+UseCompressedOops -XX:+UseCompactObjectHeaders`, plus the existing serial-GC startup flags. `fly.toml` and both `JAVA_OPTS` lines in the Dockerfile carry that string. Compressed oops stays on. The heap is not a percentage of detected RAM.

### Consequences

The archive records compressed oops and a 256 MB max heap, which is the heap the 512 MB machine runs. A large builder can no longer train a cache that machine will reject. Do not put `MaxRAMPercentage` back on these commands. 256 MB leaves the rest of the 512 MB machine for metaspace, the mapped cache, and native memory.

## Serve health before Postgres

- Status: Accepted
- Date: 2026-09-22

### Context

A Fly cold start died inside pool setup before the socket was bound, so `GET /health` never succeeded.

### Decision

`main` binds the server, then starts registration on a virtual thread. The connection pool opens on the first catalog request. `GET /health` returns `{"ok":true}` and runs no SQL. `GET /` returns identity JSON and also runs no SQL. AOT training dispatches those routes without a database.

### Consequences

`main`, `register`, `GET /health`, and `GET /` must not call `openPool`, load the JDBC driver for a query, or run catalog SQL. A refused `DATABASE_URL` still serves `/health`. Catalog routes may return 500 when Postgres is down, and the process keeps running. The liveness body is `{"ok":true}`, which is the shape `PerfTest` and the Fly check rely on.

## Read-only v1 SQL views

- Status: Accepted
- Date: 2026-08-28

### Context

The CMS rotates one language API onto the public site and falls back to Ash when none is registered. Siblings speak ordinary JSON over the v1 SQL-view contract. This repository never shipped a local `openapi.yaml`. The contract is the CMS `priv/api/openapi.yaml`.

### Decision

Queries use only `v1_speakers`, `v1_sponsors`, `v1_years`, `v1_talks`, `v1_sponsorships`, `v1_year_speakers`, and `v1_year_sponsors`. There are no writes. Ash tables and base catalog tables are out of bounds. List payloads are `{ "data": [ ... ] }`. The process registers once per boot and keeps serving when `CAROLINA_URL` is empty or the POST fails.

### Consequences

`PerfTest` rejects a `FROM` clause whose table does not start with `v1_`. Current speaker-year rows are built from `v1_speakers` and `v1_talks`. `v1_year_speakers` stays on the allow-list and is not selected by the current SQL. Year-scoped speaker rows include `languages` and `topics`. Year-scoped sponsor rows include `tier` and `blurb`.

## Vendor PostgreSQL JDBC 42.7.7

- Status: Superseded by "Vendor PostgreSQL JDBC 42.7.13"
- Date: 2026-08-28

### Context

There is no Maven build. `javac` and `java` need the PostgreSQL driver on the classpath.

### Decision

The driver was committed as `lib/postgresql-42.7.7.jar`, downloaded from Maven Central `org.postgresql:postgresql:42.7.7`.

### Consequences

The filename was the dependency coordinate. Replacing the jar is a new decision, not an edit of this one.

## Vendor PostgreSQL JDBC 42.7.13

- Status: Accepted
- Date: 2026-09-15

### Context

The check toolchain landed in the same change that replaced the 42.7.7 jar. osv-scanner reads Maven coordinates from the jar filename.

### Decision

The driver is `lib/postgresql-42.7.13.jar` from Maven Central `org.postgresql:postgresql:42.7.13`. Local runs, `make test`, and the image classpath use that file. No second JDBC jar is committed.

### Consequences

A driver bump changes the filename, the Dockerfile classpath, the Cursor install pin, `README.md`, and `MEMORY.md` in one change, and adds a superseding section here. The jar stays vendored. There is still no Maven reactor.

## Split the Make checks

- Status: Accepted
- Date: 2026-09-15

### Context

Pre-commit and the Gitea jobs need to run tests, SAST, dependency audit, secret scanning, and formatting as separate steps. One combined script hid which check failed and forced every job to run the others.

### Decision

`Makefile` exposes `test`, `sast`, `audit`, `gitleaks`, and `style`. `make check` runs all five. `make tools` only fetches JDK 27 and the pinned CLIs. Each target is a script under `scripts/`. PMD and google-java-format receive every `*.java` file from `app_java_sources`, excluding `.git` and `.tools`.

### Consequences

A new Java file is compiled only if a check script lists it, and it is scanned because discovery is by suffix. Do not hard-code `Main.java` as the SAST or format file list. Tool versions and checksums live in `scripts/tools.sh`. An emergency skip is `SKIP=local-tests,sast,audit,gitleaks,style`.

## Listen on IPv6

- Status: Accepted
- Date: 2026-09-01

### Context

The process runs on Fly 6PN, which reaches the machine over IPv6. Binding `0.0.0.0` did not accept that traffic.

### Decision

`Main.listenHost()` returns `::`. `startServer` binds that address. The source must not contain the literal `0.0.0.0`.

### Consequences

`PerfTest` fails if `Main.java` binds `0.0.0.0`, and it checks that the bound socket is an IPv6 address. Local runs and the image use the same host.
