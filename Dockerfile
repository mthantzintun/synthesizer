FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B package -DskipTests

FROM amazoncorretto:21
LABEL authors="thant"
WORKDIR /app

# the runnable uber jar, with resources inside it
COPY --from=build /build/target/synthesizer-0.0.1-SNAPSHOT.jar app.jar
# the corpus itself - pdf files and the library.bib file.
# These are copied out of the uber jar in the build stage, so that they can be mounted as a volume in the container if desired.
COPY --from=build /build/src/main/resources/papers/ /app/papers/
COPY --from=build /build/src/main/resources/data/ /app/data/

# Point the pipeline at the copied corpus rather than src/main/resources, which
# does not exist in this image.
#
# Only image-filesystem paths belong here. Network topology (the database URL,
# the GROBID URL) is declared by whoever runs the container - see the `app`
# service in docker-compose.yml. Keeping the split strict is what prevents one
# variable being overridden for containers while another is forgotten, which is
# exactly how `localhost` leaked into a containerised run.
#
# Secrets (OPENROUTER_API_KEY, LITREVIEW_DB_PASSWORD) are deliberately NOT baked
# in - pass them at run time:
#   docker run --env-file .env ...
# The app also reads a .env from the working directory if one is mounted.
ENV LITREVIEW_PDF_ROOT=/app/papers \
    LITREVIEW_BIB_FILE=file:/app/data/library.bib \
    LITREVIEW_TEI_CACHE_DIR=/app/.cache/tei

ENTRYPOINT ["java", "-jar", "app.jar"]
