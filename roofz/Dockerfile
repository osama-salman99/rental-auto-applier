FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/roofz-1.0.0.jar app.jar
# Inside a container the port mapping is the boundary, so listen on every interface
# (the service itself defaults to 127.0.0.1).
ENV SERVER_ADDRESS=0.0.0.0
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
