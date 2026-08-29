# carolina-codes-java

Read-only v1 polyglot API for Carolina Code Conference. Queries `v1_*` SQL views over `com.sun.net.httpserver` and the PostgreSQL JDBC driver.

```
mkdir -p lib
curl -fsSL -o lib/postgresql-42.7.7.jar \
  https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.7/postgresql-42.7.7.jar
javac -cp lib/postgresql-42.7.7.jar Main.java
DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5432/carolina_dev \
CAROLINA_URL=http://127.0.0.1:4000 \
POLYGLOT_REGISTER_TOKEN=dev \
PUBLIC_BASE_URL=http://127.0.0.1:4007 \
PORT=4007 \
java -cp .:lib/postgresql-42.7.7.jar Main
```
