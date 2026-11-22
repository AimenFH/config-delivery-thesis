FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/probe-service-0.3.0.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]