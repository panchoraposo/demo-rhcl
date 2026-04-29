FROM registry.access.redhat.com/ubi9/openjdk-17:1.21 AS build
USER 0
RUN microdnf install -y maven && microdnf clean all
WORKDIR /project
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:1.21
WORKDIR /work
COPY --from=build /project/target/quarkus-app/ /work/
EXPOSE 8080
ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Dquarkus.http.port=8080"
CMD ["java","-jar","quarkus-run.jar"]

