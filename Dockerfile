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
ARG NAME_APP
ARG JAR_VERSION
COPY --from=build /app/target/${NAME_APP}-${JAR_VERSION}.jar app.jar
# Puerto de la aplicación (8888), se recomienda que sea el mismo que se defina al ejecutar el contenedor en docker
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]

# Construir imagen docker
# docker build -t config-server:1.0 .

# Ejecutar imagen docker
# docker run -d -p 8888:8888 --name config-server config-server:1.0

# Creando una red de Docker
# docker network create azure-net
# docker run -p 8888:8888 --name config-server --network=azure-net config-server:1.0    #si quieres ejecutar en modo desacoplado
# docker run -d -p 8888:8888 --name config-server --network=azure-net config-server:1.0     #ejecución simple con network

# Verificar conexiones en la red Docker
# docker network inspect azure-net
