FROM eclipse-temurin:26-jdk AS build
WORKDIR /app
COPY lib ./lib
COPY Main.java .
RUN javac -cp lib/postgresql-42.7.7.jar Main.java

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /app/lib ./lib
COPY --from=build /app/*.class ./
ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=55.0 -XX:+UseG1GC -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
CMD ["sh", "-c", "exec java $JAVA_OPTS -cp .:lib/postgresql-42.7.7.jar Main"]
