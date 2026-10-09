# ---- build stage: full JDK + Maven wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
# Resolve dependencies in their own layer, so editing source code doesn't re-download them.
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

# ---- runtime stage: JRE only, non-root user ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/target/appointment-reminder-service-*.jar app.jar
USER app
ENV TZ=UTC
EXPOSE 8080
# Size the heap from the container's memory limit rather than the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
