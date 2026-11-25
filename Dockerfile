FROM eclipse-temurin:21-jdk AS build
COPY src /src
RUN javac -d /out /src/*.java

FROM eclipse-temurin:21-jre
COPY --from=build /out /app
USER nobody
# one image for both roles; compose overrides the class for the controller
CMD ["java", "-cp", "/app", "Worker"]
