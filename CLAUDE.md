# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Verifact is a Spring Boot 3.4.5 AI-powered fact-checking application that uses Spring AI (OpenAI integration) to verify news content from multiple input formats: text, URLs, images, and audio. The application employs an agentic architecture where tools are provided to the AI model for web searching, content extraction, and date/time awareness.

## Technology Stack

- **Java 17** with Spring Boot 3.4.5
- **Spring AI 1.0.0-M8** for OpenAI model integration
- **Maven** for dependency management and build
- **Tesseract OCR** (Tess4j 5.4.0) for image text extraction
- **Jsoup** for HTML parsing and web scraping
- **Google Cloud Speech API** for audio transcription
- **Google Custom Search API** for web search capabilities

## Development Commands

### Build and Run
```bash
# Build the project (skipping tests)
./mvnw clean package -DskipTests

# Run the application
./mvnw spring-boot:run

# Build with tests
./mvnw clean package
```

### Testing
```bash
# Run all tests
./mvnw test

# Run a specific test class
./mvnw test -Dtest=VerifactApplicationTests

# Run tests with coverage
./mvnw clean test jacoco:report
```

### Docker

```bash
# Using Docker Compose (recommended)
docker-compose up --build

# Or build and run manually with environment file
docker build -t verifact .
docker run -p 8080:8080 --env-file .env verifact

# Or pass environment variables directly
docker run -p 8080:8080 \
  -e OPEN_AI_API_KEY="your-key" \
  -e GOOGLE_API_KEY="your-key" \
  -e GOOGLE_SEARCH_ENGINE="your-id" \
  verifact
```

## Environment Configuration

The application requires the following environment variables:

- `OPEN_AI_API_KEY` - OpenAI API key for Spring AI
- `GOOGLE_API_KEY` - Google Custom Search API key
- `GOOGLE_SEARCH_ENGINE` - Google Custom Search Engine ID

### Setup Options

1. **Using .env file** (recommended for local development):
   - Create a `.env` file in the project root with the required variables
   - The file is already in `.gitignore` to prevent committing secrets
   - Used automatically by `docker-compose.yml`

2. **Direct environment variables**:
   - Set variables in your shell before running Maven
   - Pass via `-e` flags when using Docker

3. **Update application.properties**:
   - Modify `src/main/resources/application.properties` (not recommended for secrets)

**Note:** The application runs on port 8080 by default.

## Architecture

### Agentic AI Pattern

This application implements an **agentic AI architecture** where the LLM is given access to tools that it can call autonomously:

1. **AiService** (src/main/java/com/ai/agent/verifact/service/AiService.java:17) - Core service that:
   - Accepts user input (text, URL, or extracted content from images/audio)
   - Provides the AI model with a detailed prompt template for fact-checking
   - Registers tools with the ChatClient using `.tools(dateTimeTool, uriContentTool, googleSearchTool)`
   - The AI model decides when and how to invoke these tools based on the input

2. **Tools** - Spring AI tools (annotated with `@Tool`) that the LLM can invoke:
   - **DateTimeTool** - Provides current date/time for temporal context
   - **GoogleSearchTool** - Searches the web using Google Custom Search API
   - **UriContentTool** - Validates URLs and fetches/extracts web page content using Jsoup
   - **VoiceToTextTool** - Transcribes audio (currently placeholder implementation)

### Input Processing Flow

1. **Text Input** → AiService validates and processes directly
2. **URL Input** → UriContentTool extracts content → AiService fact-checks the content
3. **Image Input** → ImageOcrService (Tesseract) extracts text → AiService fact-checks
4. **Audio Input** → VoiceToTextTool transcribes → AiService fact-checks

### Key Components

- **AiController** (src/main/java/com/ai/agent/verifact/controller/AiController.java:16) - REST endpoints:
  - `GET /api/v1/isFakeNews?news={text}` - Text-based fact-checking
  - `POST /api/v1/analyzeImage` - Image-based fact-checking (multipart file upload)
  - `POST /api/v1/analyzeAudio` - Audio-based fact-checking (multipart file upload)

- **AiService** - Orchestrates the AI model with tool access and contains the fact-checking prompt template with specific instructions for credibility assessment, source verification, and accuracy scoring

- **ImageOcrService** (src/main/java/com/ai/agent/verifact/service/ImageOcrService.java:13) - Uses Tesseract OCR with hardcoded path `C:/Program Files/Tesseract-OCR/tessdata`

### Important Implementation Details

- The AI prompt template (src/main/java/com/ai/agent/verifact/service/AiService.java:56-97) includes:
  - Trusted source detection (BBC, CNN, Reuters, etc.) for automatic high-accuracy classification
  - Mixed real/fake content handling
  - Accuracy percentage requirements
  - Cybersecurity tips for relevant topics
  - Plain text output formatting

- **CORS Configuration** - Check `CorsConfig.java` for cross-origin settings if frontend integration is needed

- **Tesseract OCR Path** - Currently hardcoded to Windows path. For cross-platform support, this needs to be configurable via application.properties

- **VoiceToTextTool** - Currently returns placeholder text. Requires integration with actual speech-to-text service (e.g., Google Cloud Speech, Whisper API)

## Package Structure

```
com.ai.agent.verifact/
├── config/          - Spring configuration beans (CORS, RestTemplate)
├── controller/      - REST API endpoints
├── service/         - Business logic (AiService, ImageOcrService)
└── tool/            - Spring AI tools for LLM invocation
```

## Working with Spring AI Tools

When adding or modifying tools:
1. Annotate the class with `@Component`
2. Annotate methods with `@Tool(description = "clear description for the LLM")`
3. Register the tool in AiService constructor via dependency injection
4. Add to the `.tools()` call in the ChatClient prompt execution (src/main/java/com/ai/agent/verifact/service/AiService.java:110)

The LLM will autonomously decide when to invoke tools based on the prompt and tool descriptions.
