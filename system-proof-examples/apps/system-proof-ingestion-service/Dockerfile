FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY app.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
