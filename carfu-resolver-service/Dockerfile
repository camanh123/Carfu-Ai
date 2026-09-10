# Infrastructure-only image for the frozen :carfu-resolver-service JAR.
# From repo root: ./gradlew :carfu-resolver-service:installDist
#   docker build -f carfu-resolver-service/Dockerfile -t carfu-resolver-service carfu-resolver-service
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY build/install/carfu-resolver-service /app

ENV PORT=8787
EXPOSE 8787

USER nobody
CMD ["/app/bin/carfu-resolver-service"]
