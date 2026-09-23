FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
ENV MAVEN_OPTS="-Xmx256m"
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY fixtures/contract fixtures/contract
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f backend/pom.xml "-DargLine=-Xmx384m" verify

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S uitech && adduser -S -G uitech uitech \
    && mkdir -p /app /data/files && chown -R uitech:uitech /app /data
WORKDIR /app
COPY --from=build --chown=uitech:uitech /workspace/backend/target/replenishment-0.1.0.jar app.jar
USER uitech
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]
