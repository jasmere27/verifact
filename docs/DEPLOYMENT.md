# Deploying VeriFact

## Current production (2026-09-30)

| Part | Where | How to redeploy |
|---|---|---|
| Frontend | https://verifact-blf.pages.dev (Cloudflare Pages project `verifact`, direct upload incl. `functions/` for link previews, not Git-connected) | `cd frontend && VITE_API_BASE_URL=https://verifact-backend-5mux.onrender.com VITE_SUPABASE_URL=https://yappnvazdkscgqzipdxd.supabase.co VITE_SUPABASE_PUBLISHABLE_KEY=<publishable key> npm run build && npx wrangler pages deploy dist --project-name verifact --branch main` (the build also writes `dist/_headers`: CSP from these URLs; both Supabase values are public) |
| Backend | https://verifact-backend-5mux.onrender.com (Render `verifact-backend`, `srv-dad6lhrncjis7387if1g`, free plan, Singapore) | automatic on every push to `main` (or `render deploys create srv-dad6lhrncjis7387if1g`) |
| Database | Supabase project `yappnvazdkscgqzipdxd` (ap-southeast-2), Session pooler | migrations run on backend start |

Environment variables live only in the Render dashboard. After changing one, redeploy: a running deploy keeps the values it started with.

---

Target (all free or low-cost):

```
Browser ──► Cloudflare Pages (frontend/, static SPA)
               │  HTTPS, VITE_API_BASE_URL
               ▼
            Render (Spring Boot Docker container) ──► Supabase Postgres (Session pooler, IPv4)
                                                  ├─► OpenAI (chat model)
                                                  └─► Tavily (web search)
```

Expected monthly cost at low traffic: Cloudflare Pages $0 · Supabase $0 (free tier) · Render $7
(`starter`; `free` works but sleeps) · Tavily $0 up to 1,000 searches · OpenAI usage-based
(two calls per check; set a hard budget, step 2).

Order matters: database and keys → backend → frontend → connect them → smoke test.

---

## 1. Supabase (database)

1. Create a project at https://supabase.com (free tier). Pick the region closest to your users and to your Render region. Save the database password.
2. Dashboard → **Connect** → **Session pooler**. Use this one: the "Direct connection" is IPv6-only and Render can't reach it; the "Transaction pooler" (port 6543) breaks prepared statements.
3. Convert it to JDBC form:
   - `SUPABASE_DB_URL` = `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`
   - `SUPABASE_DB_USER` = `postgres.<project-ref>`
   - `SUPABASE_DB_PASSWORD` = your database password

No manual SQL: Flyway creates the tables on first start.

## 2. API keys

- **OpenAI**: create a key at https://platform.openai.com/api-keys. In **Settings → Limits**, set a monthly budget you're comfortable with; this is your cost ceiling if something goes wrong. Optionally pick the model with `SPRING_AI_OPENAI_CHAT_MODEL`.
- **Tavily**: create a key at https://app.tavily.com (1,000 free searches/month; a check uses up to 4).

## 3. GitHub

Render and Cloudflare deploy from GitHub. Push the work and merge it into `main`:

```bash
git push -u origin phase-1-verification
# open a PR into main on GitHub and merge it
```

(If `git push` asks for credentials it can't read, run `gh auth login` once, or use a personal access token.)

## 4. Render (backend)

1. https://dashboard.render.com → **New → Blueprint** → select the repository. Render reads `render.yaml` and builds the `Dockerfile` (first build takes several minutes).
2. Fill in the environment variables it asks for:

   | Variable | Value |
   |---|---|
   | `OPEN_AI_API_KEY` | from step 2 |
   | `TAVILY_API_KEY` | from step 2 |
   | `SUPABASE_DB_URL`, `SUPABASE_DB_USER`, `SUPABASE_DB_PASSWORD` | from step 1 |
   | `ALLOWED_ORIGIN` | temporarily `http://localhost:5173`; updated in step 6 |
   | `GOOGLE_API_KEY`, `GOOGLE_SEARCH_ENGINE` | leave empty (legacy) |

   `TRUST_FORWARDED_FOR=true` is preset by the blueprint.
3. When the deploy finishes, note the URL (e.g. `https://verifact-backend.onrender.com`) and check:
   ```bash
   curl https://<your-service>.onrender.com/actuator/health     # {"status":"UP"}
   ```
   Startup logs should show `Web search provider: tavily` and Flyway applying `V1` and `V2`.

## 5. Cloudflare Pages (frontend)

**Option A: Git integration (recommended; redeploys on every push)**
1. https://dash.cloudflare.com → **Workers & Pages → Create → Pages → Connect to Git** → select the repository.
2. Settings: **Root directory** `frontend` · **Build command** `npm run build` · **Build output** `dist`.
3. Environment variables: `VITE_API_BASE_URL` = your Render URL (no trailing slash), `NODE_VERSION` = `22`.
4. Deploy. You get `https://<project>.pages.dev`.

**Option B: from this machine with Wrangler**
```bash
cd frontend
VITE_API_BASE_URL=https://<your-service>.onrender.com npm run build
npx wrangler login                      # once, opens a browser
npx wrangler pages deploy dist --project-name verifact
```

`frontend/public/_redirects` makes deep links like `/r/<id>` load the app.

## 6. Connect frontend and backend

On Render, set `ALLOWED_ORIGIN` to your Pages URL (comma-separate several, e.g. a custom domain too):

```
ALLOWED_ORIGIN=https://verifact.pages.dev,https://verifact.example.com
```

Save; Render redeploys automatically.

## 7. Smoke test

```bash
API=https://<your-service>.onrender.com
curl -s $API/actuator/health
curl -s -X POST $API/api/v2/verifications -H 'Content-Type: application/json' \
     -d '{"input":"The Eiffel Tower is located in Rome."}' | head -c 600
```

Then in the browser: run a text check, a link check, open the shared `/r/<id>` link in a private window, and submit empty input (should show a friendly error).

Check the Render logs once for `X-Forwarded-For` behaviour: per-IP rate limits assume Render appends the real client IP last. If all visitors hit the limit together, set `TRUST_FORWARDED_FOR=false` and report it.

## 8. Optional

- **Custom domain**: add it in Cloudflare Pages (and add it to `ALLOWED_ORIGIN`).
- **Live quality check** (costs a few cents): `RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=VerificationEvalIT`.
- **Audio**: needs Google Cloud credentials (`GOOGLE_APPLICATION_CREDENTIALS`); without them the audio mode returns a clear "unavailable" message.

## Rollback

- Backend: Render → service → **Events** → redeploy a previous deploy.
- Frontend: Cloudflare Pages → **Deployments** → roll back.
- Database: migrations only add tables; to undo, add a new forward migration (never edit applied ones).

## Accounts (Supabase Auth, ADR-18)
- Backend (Render): `SUPABASE_URL` (project URL) to verify sign-in tokens; `SUPABASE_SECRET_KEY` (`sb_secret_...`, never in the frontend or chat) for account deletion. Without them, signed-out use works and accounts are off.
- Frontend build: `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` (public). Without them the account UI is hidden.
- Supabase dashboard: asymmetric JWT signing keys (ES256) active; Data API off; Authentication → URL Configuration: Site URL `https://verifact-blf.pages.dev`, redirect URLs `https://verifact-blf.pages.dev/**`, `http://localhost:5173/**`; Email provider with "Confirm email"; custom SMTP; minimum password length 8; Google provider (OAuth client redirect URI `https://<ref>.supabase.co/auth/v1/callback`).
- Privacy Policy `/privacy` and Terms of Use `/terms` (`frontend/src/policies/`): enter both URLs on the Google OAuth consent screen. Update them (and their "Last updated" date) whenever what we store, the services we send data to, or retention changes.
- Security headers: `frontend/scripts/write-headers.mjs` writes `dist/_headers` at build (CSP without inline scripts; the theme pre-paint script is `public/theme-init.js`). A new external origin (image host, API) must be added there, or the browser blocks it.
