FROM eclipse-temurin:21-jdk

LABEL maintainer="HertzBeat"

WORKDIR /app

# ✔ 只复制 Maven 产物
COPY target/*.jar app.jar

# JVM启动参数（可选优化）
ENV JAVA_OPTS=""

EXPOSE 1157 1158

# ✔ 直接运行 jar
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
