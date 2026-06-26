FROM eclipse-temurin:21-jdk

LABEL maintainer="Apache HertzBeat"

WORKDIR /opt/hertzbeat

# ✔ 正确：复制 jar
COPY target/*.jar app.jar

EXPOSE 1157 1158

ENTRYPOINT ["java", "-jar", "app.jar"]
