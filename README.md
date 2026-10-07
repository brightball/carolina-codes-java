# carolina-codes-java

Read-only v1 polyglot API for Carolina Code Conference. Queries `v1_*` SQL views over `com.sun.net.httpserver` and the PostgreSQL JDBC driver.

Language is Java on OpenJDK 27. The framework is `com.sun.net.httpserver`, the JDK 27 `jdk.httpserver` module, so there is no separate framework artifact. The application dependency is PostgreSQL JDBC 42.7.13, vendored at `lib/postgresql-42.7.13.jar`. The container runtime is a jlink custom runtime plus a JDK AOT cache (`-XX:AOTCache`). Startup tuning is that jlink runtime and the AOT cache. CRaC is not a dependency.

Agent contract: `AGENTS.md`. Current pins and pitfalls: `MEMORY.md`. Why those choices were made: `DECISIONS.md`.

```bash
make test        # JDK 27 javac + PerfTest on shipped Main.dispatch
make sast        # PMD security ruleset on Main.java / PerfTest.java
make audit       # osv-scanner on Maven coordinates from lib/*.jar
make gitleaks    # gitleaks detect
make style       # google-java-format --dry-run
make check       # all of the above
make hooks       # install local pre-commit hooks
```

Pre-commit runs the same five checks (`local tests`, `static security scanner`, `3rd-party dependency scanner`, `gitleaks`, `google-java-format`). Install once with `make hooks` (needs `pre-commit` on PATH). Emergency skip: `SKIP=local-tests,sast,audit,gitleaks,style git commit`. Default PATH may be Java 26; the test/SAST/style checks select JDK 27 (`$HOME/.local/jdk-27` or the GA tarball).

```
mkdir -p lib
curl -fsSL -o lib/postgresql-42.7.13.jar \
  https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar
javac -cp lib/postgresql-42.7.13.jar Main.java
DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5432/carolina_dev \
CAROLINA_URL=http://127.0.0.1:4000 \
POLYGLOT_REGISTER_TOKEN=dev \
PUBLIC_BASE_URL=http://127.0.0.1:4007 \
PORT=4007 \
java -cp .:lib/postgresql-42.7.13.jar Main
```
