FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system knowledge \
    && useradd --system --gid knowledge --home-dir /app knowledge \
    && mkdir -p /app/files /app/data/config \
    && chown -R knowledge:knowledge /app
WORKDIR /app
COPY --from=build --chown=knowledge:knowledge /app/target/knowledge-base-0.1.0.jar app.jar
USER knowledge
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
