# bytesocks Dockerfile
# Copyright (c) lucko - licenced MIT

# --------------
# BUILD PROJECT STAGE
# --------------
FROM maven:3-eclipse-temurin-21-alpine AS build-project

# compile the project
WORKDIR /bytesocks
COPY pom.xml ./
COPY src/ ./src/
RUN mvn --no-transfer-progress -B package


# --------------
# RUN STAGE
# --------------
FROM eclipse-temurin:21-alpine

RUN addgroup -S bytesocks && adduser -S -G bytesocks bytesocks
USER bytesocks

# copy app from build stage
WORKDIR /opt/bytesocks
COPY --from=build-project /bytesocks/target/bytesocks.jar .

# define a healthcheck
HEALTHCHECK --interval=1m --timeout=5s \
    CMD wget http://localhost:8080/health -q -O - | grep -c '{"status":"ok"}' || exit 1

# run the app
CMD ["java", "-jar", "bytesocks.jar"]
EXPOSE 8080/tcp
