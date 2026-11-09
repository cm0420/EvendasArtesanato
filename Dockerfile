FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Sem o profile, o application.yml cai em dev (JWT com segredo público no repo).
# prod exige JWT_SECRET e as variáveis do banco — falha ao subir em vez de subir inseguro.
ENV SPRING_PROFILES_ACTIVE=prod

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/target/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
