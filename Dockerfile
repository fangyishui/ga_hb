FROM eclipse-temurin:21-jdk

WORKDIR /opt/hertzbeat

COPY . .

RUN mvn clean package -DskipTests

RUN cp target/*.jar app.jar

EXPOSE 1157 1158

ENTRYPOINT ["java", "-jar", "app.jar"]
