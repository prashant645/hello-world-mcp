# Runtime image for the Spring Boot executable jar built on the CI runner.
FROM sapmachine:21-jre-headless-ubuntu-jammy

LABEL maintainer="GTLC Team"
LABEL description="MCP Server for Hello World Service"

# Non-root user
RUN groupadd -r appuser && useradd -r -g appuser -u 1000 appuser

WORKDIR /app

# Copy the Spring Boot fat jar produced by `bootJar`.
COPY build/libs/*.jar app.jar

RUN chown -R appuser:appuser /app

USER appuser:appuser

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
