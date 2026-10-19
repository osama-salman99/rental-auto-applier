FROM eclipse-temurin:21-jre-alpine
# The Vesteda portal (logging in, applying, withdrawing) is reached with the curl program.
RUN apk add --no-cache curl
WORKDIR /app
COPY build/libs/vesteda-1.0.0.jar app.jar
# Inside a container the port mapping is the boundary, so listen on every interface
# (the service itself defaults to 127.0.0.1).
ENV SERVER_ADDRESS=0.0.0.0
VOLUME /app/data
EXPOSE 8120
ENTRYPOINT ["java", "-jar", "app.jar"]
