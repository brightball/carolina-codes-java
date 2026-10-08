# OpenJDK 27 GA (build 35): https://jdk.java.net/27/
# eclipse-temurin:27 was not published on GA day, so the image installs the
# official GPL tarball. The shipped runtime is a jlink image plus an AOT cache
# built here. Training uses a refused connect and does not need a live database.
FROM debian:bookworm-slim AS jdk
ARG TARGETARCH=amd64
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) arch=x64; sha=95fc37eb3a18a27a26d5904c2d89d52bace8dafa9a078ca27f4747fbc4bf070b ;; \
      arm64) arch=aarch64; sha=da4e9dde1fff90204739e969187bab4751bd59a2a1c479672e1a1810f7dd23ea ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/jdk.tar.gz \
      "https://download.java.net/java/GA/jdk27/55ce5470a6294008af0057ff4626d0e5/35/GPL/openjdk-27_linux-${arch}_bin.tar.gz"; \
    echo "$sha  /tmp/jdk.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/java; \
    tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1; \
    rm /tmp/jdk.tar.gz

FROM jdk AS build
WORKDIR /app
COPY lib ./lib
COPY Main.java .
RUN /opt/java/bin/javac -cp lib/postgresql-42.7.13.jar Main.java \
    && /opt/java/bin/jar cf /app/app.jar *.class

FROM jdk AS runtime
# jlink --strip-debug invokes objcopy, which bookworm-slim does not ship.
RUN apt-get update \
    && apt-get install -y --no-install-recommends binutils \
    && rm -rf /var/lib/apt/lists/* \
    && /opt/java/bin/jlink \
      --module-path /opt/java/jmods \
      --add-modules java.base,java.sql,java.naming,java.management,java.net.http,java.security.jgss,java.desktop,java.xml,java.logging,jdk.httpserver,jdk.crypto.ec,jdk.charsets \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /opt/java-rt

FROM runtime AS aot
WORKDIR /app
COPY lib ./lib
COPY --from=build /app/app.jar ./app.jar
ENV JAVA_OPTS="-Xmx256m -XX:+UseCompressedOops -XX:+UseCompactObjectHeaders -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xss512k"
# AOT cache creation rejects a directory on the classpath, so the app is a jar.
RUN /opt/java-rt/bin/java $JAVA_OPTS -XX:AOTCacheOutput=/app/app.aot -Dcarolina.aot.train=true -cp /app/app.jar:/app/lib/postgresql-42.7.13.jar Main \
    && test -s /app/app.aot

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=runtime /opt/java-rt /opt/java
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:${PATH}"
WORKDIR /app
COPY lib ./lib
COPY --from=build /app/app.jar ./app.jar
COPY --from=aot /app/app.aot ./app.aot
ENV PORT=8080
ENV JAVA_OPTS="-Xmx256m -XX:+UseCompressedOops -XX:+UseCompactObjectHeaders -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xss512k"
EXPOSE 8080
CMD ["sh", "-c", "exec java $JAVA_OPTS -XX:AOTCache=/app/app.aot -cp /app/app.jar:/app/lib/postgresql-42.7.13.jar Main"]
