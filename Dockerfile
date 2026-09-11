# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Cache dependencies separately from source so `mvn` doesn't re-download on
# every source change.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package \
    && mv target/wallet-service.jar target/app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-alpine

# Non-root user/group to run the app as.
RUN addgroup -S wallet && adduser -S wallet -G wallet

WORKDIR /app
COPY --from=build /build/target/app.jar ./app.jar

RUN chown -R wallet:wallet /app
USER wallet

EXPOSE 8080

# wget is provided by busybox on eclipse-temurin's alpine base - no extra
# install needed. /actuator/health is public (see SecurityConfig).
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
