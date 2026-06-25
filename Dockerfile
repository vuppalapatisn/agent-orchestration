FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -U clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
COPY --from=build /workspace/target/agent-orchestration-production-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
