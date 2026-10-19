FROM eclipse-temurin:21-jre-alpine
# The ikwilhuren.nu portal (logging in, requesting viewings, withdrawing) is reached with the curl
# program.
RUN apk add --no-cache curl
WORKDIR /app
COPY build/libs/ikwilhuren-1.0.0.jar app.jar
# Inside a container the port mapping is the boundary, so listen on every interface
# (the service itself defaults to 127.0.0.1).
ENV SERVER_ADDRESS=0.0.0.0
VOLUME /app/data
EXPOSE 8100
ENTRYPOINT ["java", "-jar", "app.jar"]
