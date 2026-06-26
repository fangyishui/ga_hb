FROM eclipse-temurin:21-jdk

LABEL maintainer="Apache HertzBeat"

# 基础依赖（最小化）
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      locales \
      dos2unix \
      curl && \
    rm -rf /var/lib/apt/lists/*

# locale
RUN localedef -c -f UTF-8 -i zh_CN zh_CN.UTF-8 || true
RUN localedef -c -f UTF-8 -i en_US en_US.UTF-8 || true

ENV TZ=Asia/Shanghai
ENV LANG=en_US.UTF-8

# ⚠️ 建议你手动固定文件名（非常重要）
# 例如：apache-hertzbeat.tar.gz
ADD apache-hertzbeat.tar.gz /opt/

# 修复 shell 换行
RUN find /opt -name "*.sh" -exec dos2unix {} \; || true

WORKDIR /opt/hertzbeat

# ⚠️ 强制绝对路径，避免 ENTRYPOINT 找不到
ENTRYPOINT ["/opt/hertzbeat/bin/entrypoint.sh"]
