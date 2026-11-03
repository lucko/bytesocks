# bytesocks Dockerfile
# Copyright (c) lucko - licenced MIT

# --------------
# BUILD PROJECT STAGE
# --------------
FROM maven:3-eclipse-temurin-25-alpine AS build-project

# compile the project
WORKDIR /bytesocks
COPY pom.xml ./
COPY src/ ./src/
RUN mvn --no-transfer-progress -B package


# --------------
# RUNTIME BASE
# --------------
FROM eclipse-temurin:25-alpine AS runtime

RUN addgroup -S bytesocks && adduser -S -G bytesocks bytesocks
USER bytesocks

WORKDIR /opt/bytesocks

# define a healthcheck
HEALTHCHECK --interval=1m --timeout=5s \
    CMD wget http://localhost:8080/health -q -O - | grep -c '{"status":"ok"}' || exit 1

# run the app
CMD ["java", "-jar", "bytesocks.jar"]
EXPOSE 8080/tcp


# --------------
# PREBUILT TARGET
# --------------
FROM runtime AS prebuilt
COPY target/bytesocks.jar .


# --------------
# DEFAULT TARGET
# --------------
FROM runtime
COPY --from=build-project /bytesocks/target/bytesocks.jar .
