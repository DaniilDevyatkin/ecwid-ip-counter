# Build stage: compile and run tests with Maven inside the image.
FROM eclipse-temurin:21-jdk AS build
RUN apt-get -o Acquire::Retries=3 update -qq \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq maven \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /w
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package \
 && mkdir -p /app/classes \
 && cp -r target/classes/* /app/classes/

FROM eclipse-temurin:21-jre
COPY --from=build /app/classes /app/classes
WORKDIR /app
ENTRYPOINT ["java", "-Xmx1200m", "-cp", "/app/classes", "ipcounter.Main", "--demo", "/tmp"]
