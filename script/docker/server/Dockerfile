FROM eclipse-temurin:17-jre

# 设置环境变量，比如时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 创建运行所需的目录
RUN mkdir -p /opt/hertzbeat/bin \
    && mkdir -p /opt/hertzbeat/data \
    && mkdir -p /opt/hertzbeat/config \
    && mkdir -p /opt/hertzbeat/ext-lib

# ----------------- 核心修改区 -----------------
# 1. 复制从 Actions 搬运过来的 Jar 包
COPY target/app.jar /opt/hertzbeat/app.jar

# 2. 复制从 Actions 搬运过来的启动脚本
COPY entrypoint.sh /opt/hertzbeat/bin/entrypoint.sh

# 3. 赋予脚本执行权限
RUN chmod +x /opt/hertzbeat/bin/entrypoint.sh

# 设置工作目录
WORKDIR /opt/hertzbeat

# 对外暴露所需端口
EXPOSE 1157 1158

# 设置启动入口点
ENTRYPOINT ["/opt/hertzbeat/bin/entrypoint.sh"]
