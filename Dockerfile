# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Dependencies first so they are cached until pom.xml changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Run ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Tesseract OCR (used by ImageOcrService via tess4j).
# The traineddata path differs by base-OS/package version, so discover it at
# build time and expose it at a stable location instead of hardcoding it.
RUN apt-get update && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-eng \
    && TESSDATA_DIR="$(dirname "$(find / -name eng.traineddata 2>/dev/null | head -n1)")" \
    && ln -s "$TESSDATA_DIR" /usr/share/tessdata \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home verifact

ENV TESSDATA_PATH=/usr/share/tessdata
# Size the heap from the container limit (small instances) and restart cleanly on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

COPY --from=build --chown=verifact /app/target/*.jar app.jar
USER verifact

# The platform may set PORT; defaults to 8080 (see server.port).
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
