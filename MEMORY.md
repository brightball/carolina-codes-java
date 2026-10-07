# Memory

Operational notes for this repository: version pins, commands, and pitfalls. Edit this file in place when a pin or a command changes. The reason for a choice belongs in `DECISIONS.md`. Do not copy decision history here.

For this OpenJDK 27 and `com.sun.net.httpserver` tree, the JDK specification version is the framework version. There is no separate framework coordinate to remember.

## Pins

| What | Value | Enforced in |
| --- | --- | --- |
| Language | Java, OpenJDK 27 (GA build 35) | `scripts/java-home.sh`, `Dockerfile`, `PerfTest` |
| Framework | `com.sun.net.httpserver` (module `jdk.httpserver`) | `Main.FRAMEWORK` |
| PostgreSQL JDBC | 42.7.13, file `lib/postgresql-42.7.13.jar` | classpath in `Dockerfile` and `scripts/test.sh` |
| google-java-format | 1.36.1 | `scripts/tools.sh` |
| PMD | 7.27.0 | `scripts/tools.sh` |
| osv-scanner | 2.6.0 | `scripts/tools.sh` |
| gitleaks | 8.30.1 | `scripts/tools.sh` |
| API version | 0.2.0 | `Main.API_VERSION` |
| Schema version | 1 | `Main.SCHEMA_VERSION` |
| Created year | 2026 | `Main.CREATED_YEAR` |
| Local `PORT` | 4007 | `Main.main` default |
| Image and Fly `PORT` | 8080 | `Dockerfile`, `fly.toml` |
| Pool | 8 connections, 10 second wait, opened on the first catalog request | `Main` |
| Listen | `::` | `Main.listenHost` |
| Health body | `{"ok":true}` | `Main.dispatch` |

jlink modules, from the Dockerfile: `java.base`, `java.sql`, `java.naming`, `java.management`, `java.net.http`, `java.security.jgss`, `java.desktop`, `java.xml`, `java.logging`, `jdk.httpserver`, `jdk.crypto.ec`, `jdk.charsets`.

`JAVA_OPTS` in `fly.toml` and the image `ENV` are the same string. `PerfTest` checks that. The flags include `-XX:MaxRAMPercentage=55.0`, `-XX:+UseSerialGC`, `-XX:ActiveProcessorCount=1`, `-XX:+ExitOnOutOfMemoryError`, `-XX:TieredStopAtLevel=1`, `-XX:CICompilerCount=1`, and `-Xss512k`. The process also loads `-XX:AOTCache=/app/app.aot`.

Checksums for the JDK tarball and the check CLIs stay in `Dockerfile`, `scripts/java-home.sh`, and `scripts/tools.sh`. Do not copy the hashes into this file.

Dev examples used by the README and the tests: `DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5432/carolina_dev`, `CAROLINA_URL=http://127.0.0.1:4000`, `POLYGLOT_REGISTER_TOKEN=dev`.

## Commands

```bash
make test        # JDK 27 javac + PerfTest. No Postgres
make sast        # PMD on every application .java file
make audit       # osv-scanner on Maven coordinates from lib/*.jar
make gitleaks    # gitleaks detect
make style       # google-java-format --dry-run
make check       # all five
make tools       # fetch JDK 27 and the pinned CLIs
make hooks       # install pre-commit hooks
```

`scripts/test.sh` compiles `Main.java` and `PerfTest.java` with `lib/postgresql-42.7.13.jar` and runs `PerfTest`. The local server command is in `README.md`.

## Pitfalls

- `java` on `PATH` may be 26. A home counts only when `java.specification.version` is 27. Checks call `scripts/java-home.sh`.
- `sha256sum` output in `scripts/java-home.sh` and `scripts/tools.sh` stays on stderr. `java-home.sh` prints only the home path on stdout. A checksum printed on stdout breaks `JAVA_HOME`.
- AOT cache creation rejects a directory on the classpath. The image runs `/app/app.jar` plus the JDBC jar.
- AOT training is `-Dcarolina.aot.train=true`. It loads the driver, dispatches `/health`, `/`, and `/v1/years`, binds port 0, and exits. It does not need a reachable database.
- `GET /health` and `GET /` must not open JDBC or run SQL. The pool opens on the first catalog request.
- Registration runs on a virtual thread after `startServer` and must not open the pool or run catalog SQL. An empty `CAROLINA_URL` or token skips it. A failed POST is logged and the server keeps running.
- Bind `::`. `Main.java` must not contain `0.0.0.0`.
- `jlink --strip-debug` invokes `objcopy`. The build image installs `binutils` for that.
- SAST and format take the file list from `app_java_sources`. Do not freeze the list at `Main.java`.
- The OpenAPI document is not in this repo. It is the CMS `priv/api/openapi.yaml`.
- Handler tests use a fake catalog. Postgres is only for a live HTTP check against the CMS views.
- CRaC is not configured. Startup tuning is the jlink runtime and `-XX:AOTCache`.
