FROM eclipse-temurin:21-jdk

LABEL maintainer="Apache HertzBeat <dev@hertzbeat.apache.org>"

RUN apt-get update && \
    apt-get install -y --no-install-recommends locales dos2unix curl && \
    rm -rf /var/lib/apt/lists/*

RUN localedef -c -f UTF-8 -i zh_CN zh_CN.UTF-8 || true
RUN localedef -c -f UTF-8 -i en_US en_US.UTF-8 || true

ENV TZ=Asia/Shanghai
ENV LANG=en_US.UTF-8

# ⚠️ 建议固定文件名（CI里统一 rename）
ADD apache-hertzbeat.tar.gz /opt/

RUN find /opt -name "*.sh" -exec dos2unix {} \; || true

WORKDIR /opt/hertzbeat

ENTRYPOINT ["/opt/hertzbeat/bin/entrypoint.sh"]
