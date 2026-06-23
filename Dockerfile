# 1. Asama: Maven ile projeyi derleme
FROM maven:3.8.5-openjdk-17 AS build
COPY . .
RUN mvn clean package -DskipTests

# 2. Asama: Sadece JAR dosyasını calıstıracak hafif imaj
FROM openjdk:17-jdk-slim
COPY --from=build /target/SorumlulukStokTakipWeb-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
