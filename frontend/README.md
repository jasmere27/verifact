# VeriFact frontend

*Verify Truth, Fight the False.*

The web client for VeriFact. You paste a claim, an article, or a link, or upload an image or a short WAV clip. VeriFact
pulls out the specific factual claims, searches the web for sources, and returns a structured report. The report gives
a verdict and evidence strength for each claim, lists the supporting and contradicting sources with quotes, and says
what remains uncertain.

Built with React 19, Vite and TypeScript. It has no router or UI library: a small pathname router (`src/router.ts`)
serves `/` (check) and `/r/{id}` (shareable report).

## Configuration

| Variable | Purpose | Example |
|---|---|---|
| `VITE_API_BASE_URL` | Base URL of the VeriFact backend (v2 API). Read at build time. | `http://localhost:8080` |

Copy `.env.example` to `.env.local` for local development.

## Scripts

```bash
npm ci            # install
npm run dev       # dev server with HMR
npm run build     # type-check and build to dist/
npm run lint      # oxlint
npm run preview   # serve the production build locally
```

## How it talks to the backend

All requests go through `src/api.ts`:

- `POST /api/v2/verifications`: text or a link (`{"input": "..."}`)
- `POST /api/v2/verifications/image` and `/audio`: multipart `file`
- `GET /api/v2/verifications/{id}`: a saved report (used by `/r/{id}`)

Errors are RFC 9457 problem+json. The UI shows the `detail`, the `X-Request-Id` as a reference, and `Retry-After`
when the server rate-limits (429). API text is always rendered as plain text, never as HTML.

"Recent checks" are stored only in the browser's `localStorage`: the last 10 report IDs, with each report's verdict and label.

## Deploy (Cloudflare Pages)

- Root directory: `frontend`
- Build command: `npm run build`
- Build output directory: `dist`
- Environment variable: `VITE_API_BASE_URL`, set to the public backend URL

`public/_redirects` (`/*  /index.html  200`) makes deep links such as `/r/{id}` load the app.
The backend's `ALLOWED_ORIGIN` must include the Pages domain.
