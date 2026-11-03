# syntax=docker/dockerfile:1

ARG JDK_IMAGE=eclipse-temurin:25-jdk-alpine
ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine

# ---- build stage ---------------------------------------------------------
FROM ${JDK_IMAGE} AS build
WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
# Optional Gradle init script (e.g. a corporate Maven mirror):
#   docker build --secret id=gradle-init,src=path/to/init.gradle .
RUN --mount=type=secret,id=gradle-init,target=/root/.gradle/init.d/custom.gradle \
    chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN --mount=type=secret,id=gradle-init,target=/root/.gradle/init.d/custom.gradle \
    ./gradlew --no-daemon bootJar -x test \
    && java -Djarmode=tools -jar build/libs/*-SNAPSHOT.jar extract --layers --destination extracted

# ---- runtime stage -------------------------------------------------------
FROM ${JRE_IMAGE} AS runtime
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

ENV SERVER_PORT=8089 \
    JAVA_OPTS=""
EXPOSE 8089

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar closing-*.jar"]
