FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/config-server-0.1.0.jar app.jar

EXPOSE 8888
ENTRYPOINT ["java", "-jar", "/app/app.jar"]