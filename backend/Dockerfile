# syntax=docker/dockerfile:1
# Production image of the backend (ADR-0011). Build context: backend/.
#   docker build -t chat-app:local backend
# The jar is built with -PprodJar: it does NOT contain application-dev.yml or application-bench.yml
# (verifyProdJar fails the build otherwise). With no profile and no JWT_SECRET the app refuses to start.

# The official Gradle image pins the same Gradle as the wrapper (8.10.2) without downloading it at build time.
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
# Dependencies in their own layer, so a source-only change doesn't re-download them.
RUN gradle --no-daemon -q dependencies > /dev/null
COPY src/main src/main
RUN gradle --no-daemon -q -PprodJar bootJar verifyProdJar -x test

FROM eclipse-temurin:21.0.5_11-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/build/libs/chatplatform-backend-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
# Heap as a share of the container's memory limit (compose sets mem_limit), not of the host's RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
