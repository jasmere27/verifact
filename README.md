# Verifact - AI-Powered Fact-Checking System

Verifact is a Spring Boot application (with a companion React frontend in `frontend/`) that leverages AI to verify the authenticity of news and information from multiple sources including text, URLs, images, and audio files. Every check is persisted to a Postgres database (Supabase) and browsable via `/api/v1/history`.

## Features

- **Text-based fact-checking**: Verify news articles and statements directly
- **URL content analysis**: Extract and analyze content from web pages
- **Image-based verification**: Use OCR to extract text from images and verify claims
- **Audio transcription**: Convert audio files to text and fact-check the content
- **Intelligent web search**: Automatically searches credible sources for verification
- **Accuracy scoring**: Provides percentage-based accuracy assessments
- **Source verification**: Checks against trusted news sources (BBC, CNN, Reuters, etc.)
- **Cybersecurity tips**: Offers security advice for relevant topics

## Technology Stack

- **Java 17**
- **Spring Boot 3.4.5**
- **Spring AI 1.0.0-M8** (OpenAI integration)
- **Maven** for build management
- **Tesseract OCR** for image text extraction
- **Jsoup** for HTML parsing
- **Google Custom Search API** for web search
- **Google Cloud Speech API** for audio transcription
- **Docker** for containerization
- **Supabase (Postgres)** for persisting fact-check history, via Spring Data JPA + Flyway
- **React + Vite (TypeScript)** frontend, deployed on Cloudflare Pages

## Prerequisites

### For Docker-based Setup (Recommended - No Java Required)
- Docker and Docker Compose
- A Supabase project (free tier is fine) — see [Database Setup](#database-setup-supabase)
- API Keys:
  - OpenAI API key
  - Google Custom Search API key
  - Google Search Engine ID

### For Local Development (Optional)
- Java 17 or higher
- Maven 3.6+
- Tesseract OCR (for image analysis features)
- Node.js 18+ (only if working on `frontend/`)
- API Keys and Supabase project (same as above)

## Getting Started

### 1. Clone the Repository

```bash
git clone <repository-url>
cd verifact-backend
```

### 2. Configure Environment Variables

Copy `.env.example` to `.env` in the project root and fill in the values:

```env
OPEN_AI_API_KEY=your-openai-api-key
GOOGLE_API_KEY=your-google-api-key
GOOGLE_SEARCH_ENGINE=your-search-engine-id

# From your Supabase project (Project Settings -> Database -> Connection string)
SUPABASE_DB_URL=jdbc:postgresql://<project-ref>.supabase.co:5432/postgres
SUPABASE_DB_USER=postgres
SUPABASE_DB_PASSWORD=your-db-password

# Origin(s) allowed to call the API, comma-separated
ALLOWED_ORIGIN=http://localhost:3000
```

**Important**: Never commit the `.env` file to version control. It's already included in `.gitignore`.

### Database Setup (Supabase)

1. Create a project at [supabase.com](https://supabase.com).
2. Grab the Postgres connection string from **Project Settings → Database → Connection string** (use the direct connection, port `5432`, for this long-running Spring Boot app rather than the transaction pooler).
3. Set `SUPABASE_DB_URL` / `SUPABASE_DB_USER` / `SUPABASE_DB_PASSWORD` accordingly.
4. No manual schema setup needed — Flyway runs `src/main/resources/db/migration/V1__init.sql` automatically on startup and creates the `fact_check_results` table.

### 3. Run the Application

#### Option A: Using build.sh Script (Recommended - No Java Required)

The `build.sh` script automatically builds and runs the application on port 8080:

```bash
# Make the script executable (first time only)
chmod +x build.sh

# Build with Docker and run (DEFAULT - no Java required locally)
./build.sh
# or explicitly:
./build.sh docker

# Build with Maven and run (requires Java 17+ installed)
./build.sh maven

# Build both Maven and Docker, then run
./build.sh all

# Clean all build artifacts
./build.sh clean
```

**What the script does:**
1. Builds the project inside a Docker container (Maven + Java 17 included)
2. Creates a Docker image with the application
3. Automatically starts the application on port 8080 via Docker Compose
4. Press `Ctrl+C` to stop the application

**No Java installation required** - Everything runs inside Docker containers!

#### Option B: Using Maven

```bash
# Build and run
./mvnw spring-boot:run

# Or build first, then run
./mvnw clean package -DskipTests
java -jar target/verifact-0.0.1-SNAPSHOT.jar
```

#### Option C: Using Docker Compose

```bash
docker-compose up --build
```

#### Option D: Using Docker

```bash
docker build -t verifact .
docker run -p 8080:8080 --env-file .env verifact
```

The application will start on `http://localhost:8080`

### 4. Run the Frontend (optional, for local dev)

```bash
cd frontend
cp .env.example .env   # defaults to http://localhost:8080
npm install
npm run dev
```

Opens on `http://localhost:5173` by default.

## API Endpoints

### 1. Text-based Fact-Checking

**Endpoint**: `GET /api/v1/isFakeNews`

**Parameters**:
- `news` (string, required): The text or URL to fact-check

**Example**:
```bash
curl "http://localhost:8080/api/v1/isFakeNews?news=Your%20news%20statement%20here"
```

### 2. Image-based Fact-Checking

**Endpoint**: `POST /api/v1/analyzeImage`

**Parameters**:
- `file` (multipart file, required): Image file containing text

**Example**:
```bash
curl -X POST http://localhost:8080/api/v1/analyzeImage \
  -F "file=@/path/to/image.jpg"
```

### 3. Audio-based Fact-Checking

**Endpoint**: `POST /api/v1/analyzeAudio`

**Parameters**:
- `file` (multipart file, required): Audio file to transcribe and verify

**Example**:
```bash
curl -X POST http://localhost:8080/api/v1/analyzeAudio \
  -F "file=@/path/to/audio.wav"
```

### 4. Fact-Check History (disabled by default)

**Endpoints**: `GET /api/v1/history?page=0&size=20`, `GET /api/v1/history/{id}`

There are no user accounts yet, so these would expose every visitor's submissions to everyone. They return `404` unless `HISTORY_API_ENABLED=true`, which is intended for local development only.

### Limits and errors

- Text input: at most 10,000 characters (`MAX_INPUT_CHARS`). Uploads: at most 10 MB. Images: JPEG, PNG, GIF, BMP, or TIFF. Audio: LINEAR16 WAV, English, under about one minute.
- Links are fetched only if they use http/https on standard ports and resolve to public addresses (no localhost, private networks, or cloud metadata). Redirects are re-checked.
- Rate limits on the three verification endpoints: 5 per minute and 50 per day per IP, and 1,000 per day in total (all configurable, see `.env.example`). Exceeding them returns `429` with `Retry-After`.
- Successful checks return `200 text/plain`. Errors return a non-2xx status with an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json` body whose `detail` is safe to show users, plus a `requestId` (also sent as the `X-Request-Id` header) for matching server logs.

### Health check

`GET /actuator/health` returns `{"status":"UP"}`. It makes no AI or search calls; use it for platform health checks.

## How It Works

### Agentic AI Architecture

Verifact uses an **agentic AI pattern** where the AI model is equipped with tools it can invoke autonomously:

1. **User Input**: Accept text, URL, image, or audio
2. **Content Extraction**: Extract text from images (OCR) or audio (speech-to-text)
3. **Tool-Augmented Analysis**: The AI model can call two tools:
   - `DateTimeTool`: Get current date/time for temporal context
   - `GoogleSearchTool`: Search the web; results include title, URL, and snippet, and only those URLs may be cited

   Links submitted by users are fetched by the backend (`SafeUrlFetcher`), never by the model. All submitted and fetched content is passed to the model as clearly delimited, untrusted data.
4. **Fact-Checking**: Analyze against credible sources
5. **Response**: Return verdict with accuracy score and sources

### Trusted Sources

The system prioritizes content from:
- BBC News (bbc.com)
- CNN (cnn.com)
- Reuters (reuters.com)
- The Guardian (theguardian.com)
- Associated Press (apnews.com)
- New York Times (nytimes.com)

## Development

### Running Tests

```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=VerifactApplicationTests

```

Tests need no API keys, database, or network: the AI model and search are faked, and an in-memory H2 database stands in for Postgres (`src/test/resources/application-test.properties`). Tesseract is not required either; OCR itself is not exercised by tests.

Frontend checks:

```bash
cd frontend
npm ci
npm run build   # type check + production build
npm run lint
```

### Building for Production

#### Using build.sh (Recommended - No Java Required)

```bash
# Build Docker image (no Java installation needed)
./build.sh docker

# Build with Maven (requires Java 17+ installed locally)
./build.sh maven

# Build both
./build.sh all
```

**Note**: The build.sh script automatically runs the application after building. Press `Ctrl+C` to stop it if you only want to build without running.

**For production deployment**, the Docker image (`verifact:latest`) is built with all dependencies included. You can deploy it to any Docker-compatible environment without needing Java installed on the host machine.

#### Manual Build

```bash
./mvnw clean package
```

The JAR file will be created in the `target/` directory.

## Project Structure

```
verifact/
├── src/main/java/com/ai/agent/verifact/
│   ├── config/          # Configuration classes (CORS, beans)
│   ├── controller/      # REST API controllers
│   ├── model/           # JPA entities (FactCheckResult, InputType)
│   ├── repository/      # Spring Data repositories
│   ├── service/         # Business logic services
│   ├── tool/            # Spring AI tools for LLM
│   └── VerifactApplication.java
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/    # Flyway SQL migrations
├── src/test/
├── frontend/             # React + Vite SPA (deployed to Cloudflare Pages)
├── .env                  # Environment variables (not in git)
├── build.sh              # Build and run script
├── docker-compose.yml    # Docker Compose configuration
├── Dockerfile             # Docker image definition (backend)
├── render.yaml            # Render Blueprint (backend deploy)
└── pom.xml                # Maven dependencies
```

## Configuration

### Environment variables

All configuration comes from environment variables; `.env.example` lists every variable with safe placeholders and defaults. The mapping to Spring properties is in `src/main/resources/application.properties`.

### Tesseract OCR Configuration

The tessdata path is configurable via the `TESSDATA_PATH` environment variable (`tesseract.datapath` property), defaulting to `/usr/share/tesseract-ocr/5/tessdata` for local Linux dev. The Docker image installs `tesseract-ocr` at build time and sets `TESSDATA_PATH` automatically — no manual configuration needed for Docker/Render deploys. For local Windows development without Docker, set `TESSDATA_PATH` to your Tesseract-OCR install's `tessdata` folder (e.g. `C:/Program Files/Tesseract-OCR/tessdata`).

## Deployment

Cloudflare Pages/Workers cannot run this JVM application directly, so the backend and frontend deploy to different platforms:

### Backend → Render

1. Push this repo to GitHub and create a new **Blueprint** on [Render](https://render.com) pointing at it — it picks up `render.yaml` and the existing `Dockerfile` automatically.
2. In the Render dashboard, set the env vars declared in `render.yaml` (`OPEN_AI_API_KEY`, `GOOGLE_API_KEY`, `GOOGLE_SEARCH_ENGINE`, `SUPABASE_DB_URL`, `SUPABASE_DB_USER`, `SUPABASE_DB_PASSWORD`, `ALLOWED_ORIGIN`) with your real values — none of these are committed to the repo. `TRUST_FORWARDED_FOR=true` is set by the blueprint so rate limits see real client IPs; the health check uses `/actuator/health`.
   `render.yaml` uses the paid `starter` plan (the free plan sleeps after 15 minutes idle and has 512 MB RAM, which is tight for the JVM plus Tesseract). Change `plan` if you prefer.
3. Deploy. Render builds the Docker image and exposes the service on its own `https://<service>.onrender.com` URL.

### Frontend → Cloudflare Pages

1. Create a Cloudflare Pages project connected to this repo, with **root directory** set to `frontend`.
2. Build command: `npm run build`. Output directory: `dist`.
3. Set the `VITE_API_BASE_URL` environment variable to your Render backend URL.
4. Once deployed, set `ALLOWED_ORIGIN` on the Render backend to the resulting `https://<project>.pages.dev` URL (comma-separate multiple origins if needed) so CORS allows it.

## Known Limitations

- **Google Custom Search JSON API is discontinued on 2027-01-01** and closed to new customers. A replacement search provider is planned.
- **Audio** uses Google Cloud Speech, which needs Google Application Default Credentials (`GOOGLE_APPLICATION_CREDENTIALS`); without them the audio endpoint returns `503`.
- **Images** are converted to text with OCR; the model never sees the image itself.
- Verdicts (`real`/`fake`/`mixed`/`unverified`) and confidence scores come from the model's report. A structured, evidence-cited verdict model is planned.
- Rate limits are in-memory, so they reset on restart and apply per backend instance.

See `.claude/memory/known-issues.md` for the full, ranked list.

## Security Considerations

- Never commit API keys or secrets to version control
- The `.env` file is excluded via `.gitignore`
- Rotate API keys immediately if accidentally exposed
- Use environment variables for sensitive configuration
- Submitted text and fetched web pages are treated as untrusted input: server-side fetches are SSRF-guarded, and content is delimited in the prompt with instructions to ignore embedded commands
- Error responses never include stack traces or internal exception messages

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License.

## Support

For issues, questions, or contributions, please open an issue on the GitHub repository.

---

**Built with Spring AI and powered by OpenAI**
