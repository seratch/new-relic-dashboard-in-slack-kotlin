FROM gradle:6.0.1-jdk11 AS build
COPY . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build

FROM openjdk:12
COPY --from=build /home/gradle/src/build/libs/new-relic-slack-app-home-*.jar /app/new-relic-slack-app-home.jar
CMD ["java",  "-jar", "/app/new-relic-slack-app-home.jar"]