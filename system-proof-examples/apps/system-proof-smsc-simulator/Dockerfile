FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY app.jar app.jar
USER 10001
EXPOSE 2775 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
