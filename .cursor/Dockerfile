# OpenJDK 27 GA (build 35): https://jdk.java.net/27/
# eclipse-temurin:27 was not published on GA day.
FROM debian:bookworm-slim
USER root
ARG TARGETARCH=amd64
RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* || true
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
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:${PATH}"
