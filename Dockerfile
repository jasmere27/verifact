# Stage 1: Build Spring Boot app - edit
FROM maven:3.9.2-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Stage 2: Run the app
FROM eclipse-temurin:17-jre
WORKDIR /app

# Tesseract OCR (ImageOcrService shells out to it via tess4j).
# The traineddata path differs by base-OS/package version, so discover it at
# build time and expose it at a stable location instead of hardcoding it.
RUN apt-get update && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-eng \
    && TESSDATA_DIR="$(dirname "$(find / -name eng.traineddata 2>/dev/null | head -n1)")" \
    && ln -s "$TESSDATA_DIR" /usr/share/tessdata \
    && rm -rf /var/lib/apt/lists/*

ENV TESSDATA_PATH=/usr/share/tessdata

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]
