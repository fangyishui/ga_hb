FROM eclipse-temurin:17-jre
# 将搬运过来的 jar 和脚本拷贝进容器
COPY target/app.jar /opt/hertzbeat/app.jar
COPY entrypoint.sh /opt/hertzbeat/bin/entrypoint.sh
# 赋予执行权限
RUN chmod +x /opt/hertzbeat/bin/entrypoint.sh
WORKDIR /opt/hertzbeat
ENTRYPOINT ["/opt/hertzbeat/bin/entrypoint.sh"]
