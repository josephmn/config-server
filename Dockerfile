# Etapa de construccion
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Etapa final
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=build /app/target/config-server-1.0.0.jar ./config-server-1.0.0.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "config-server-1.0.0.jar"]

# Construir imagen docker
# docker build -t config-server:1.0 .

# Ejecutar imagen docker
# docker run -d -p 8888:8888 --name config-server config-server:1.0