FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew* gradle* build.gradle settings.gradle ./
COPY libs ./libs
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN mkdir -p /app/storage/documents
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
