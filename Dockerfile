FROM eclipse-temurin:26-jdk
WORKDIR /app
COPY lib ./lib
COPY Main.java .
RUN javac -cp lib/postgresql-42.7.7.jar Main.java
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", ".:lib/postgresql-42.7.7.jar", "Main"]
