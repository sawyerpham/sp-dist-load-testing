FROM eclipse-temurin:21-jdk AS build
COPY src /src
RUN javac -d /out /src/*.java

FROM eclipse-temurin:21-jre
COPY --from=build /out /app
USER nobody
CMD ["java", "-cp", "/app", "Server"]
