FROM eclipse-temurin:25-jdk-alpine AS gradle-base

WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon help

FROM gradle-base AS development

COPY src src
CMD ["./gradlew", "bootRun", "--no-daemon"]

FROM gradle-base AS build

COPY src src
RUN ./gradlew bootJar --no-daemon

FROM build AS test

RUN ./gradlew test --no-daemon

FROM build AS integration-test

CMD ["./gradlew", "integrationTest", "--no-daemon"]

FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S iam && adduser -S -G iam iam
WORKDIR /app
COPY --from=build /app/build/libs/iam-0.0.1-SNAPSHOT.jar /app/iam.jar
USER iam
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/iam.jar"]
