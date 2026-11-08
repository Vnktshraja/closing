## Minimal two-stage Dockerfile: build then run a single jar
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

COPY --from=build /workspace/build/libs/closing-*.jar ./closing-app.jar

ENV SERVER_PORT=8089
ENV JAVA_OPTS=""
EXPOSE 8089

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar closing-app.jar"]
