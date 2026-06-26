FROM eclipse-temurin:21-jdk

LABEL maintainer="Apache HertzBeat"

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    locales dos2unix curl && \
    rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai
ENV LANG=en_US.UTF-8

WORKDIR /opt/hertzbeat

# ✅ 关键：直接复制构建产物（CI生成）
COPY target/hertzbeat /opt/hertzbeat

RUN find /opt/hertzbeat -name "*.sh" -exec dos2unix {} \; || true

ENTRYPOINT ["/opt/hertzbeat/bin/entrypoint.sh"]
