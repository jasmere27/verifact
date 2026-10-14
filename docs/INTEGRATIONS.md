# Integrations: VeriFact Intelligence

_Last updated: 2026-10-14. Facts about third parties were researched on 2026-10-14; anything marked
UNVERIFIED must be confirmed before relying on it._

Every integration below fails visibly: a missing credential makes the feature report itself unavailable
(HTTP 503 or an "UNAVAILABLE" source in a monitoring run). Nothing is simulated.

## Summary

| Integration | Used for | Status in code | Needs |
|---|---|---|---|
| Tavily Search | Web search + news search connectors, investigation evidence | Implemented (existing provider + new `searchRecent`) | `TAVILY_API_KEY` (already set in production) |
| OpenAI (via Spring AI) | Claim extraction from mentions, investigation assessment, Response Studio | Implemented (existing `LlmClient`) | `OPEN_AI_API_KEY` (already set) |
| YouTube Data API v3 | YouTube connector (video titles, channels, descriptions) | Implemented | `YOUTUBE_API_KEY` (already set for NewsFact) |
| Public RSS/Atom feeds | RSS connector | Implemented | Nothing; customer supplies feed URLs |
| Public web pages | Page-watch connector | Implemented | Nothing; customer supplies URLs |
| Paddle Billing | Checkout, plan changes, cancellation, customer portal, webhooks | Implemented; **not connected** | Paddle seller account approval, API key, webhook secret, price ids |
| External cron | Wakes a sleeping host to run due monitoring | Endpoint implemented | `MONITORING_CRON_SECRET` + a cron service |
| Email (alerts, scheduled reports) | — | **Not implemented** | An email provider; see "Not built" |
| Social platforms (X, Reddit, …) | — | **Not implemented** | Paid/approved API access; see below |

## Search: Tavily

- Endpoint `POST https://api.tavily.com/search`. Monitoring uses `topic` = `general` (web) or `news`, and
  `time_range` = `day`/`week`/`month`/`year` from the project's look-back. `search_depth: basic` = 1 credit.
  Source: https://docs.tavily.com/documentation/api-reference/endpoint/search (checked 2026-10-14).
- Pricing: 1,000 free credits/month, then $0.008/credit (https://docs.tavily.com/documentation/api-credits).
- Budget per monitoring run: at most `MONITORING_MAX_QUERIES` (default 4) queries per search connector, so ≤ 8
  credits for a project with web + news. Per investigation: ≤ 10 searches (shared NewsFact pipeline).
- `published_date` is beta and may be null; undated items are kept but left out of chronologies.

## Feeds and pages

- Fetched only through `SafeUrlFetcher`: http(s) only, every redirect hop re-validated by `UrlGuard`
  (private, loopback, link-local and metadata addresses blocked), 10 s timeout, 2 MB cap.
- Only headline, summary text and link are stored for feed items; page watching stores up to three excerpts
  around brand mentions, keyed by the page's content hash.
- **Google News RSS must not be used**: its terms allow personal, non-commercial feed-reader use only
  (https://news.google.com/news/rss/terms). The project form says so.
- Login-protected pages return errors and are reported as failed sources; nothing tries to bypass access controls.
- Considered, not built: GDELT DOC 2.0 (free news search over the last 3 months; commercial-use terms and rate
  limits UNVERIFIED). Worth evaluating as a cheap supplementary news connector.

## Social media (not built)

| Platform | State (2026-10) | Notes |
|---|---|---|
| X | Pay-per-use API, ~$0.005 per post read (https://docs.x.com/x-api/getting-started/pricing) | Feasible but metered; search endpoint pricing UNVERIFIED |
| Reddit | Data API approval-gated; commercial use needs an agreement | Treat as unavailable without approval |
| YouTube | Data API v3, `search.list` = 100 quota units of 10,000/day default | **Built** (`YouTubeConnector`): see below |

The product says plainly which social platforms are not monitored (project form, Settings, every report).

### YouTube connector
- `search.list` with `publishedAfter` = the project's look-back and `order=date`, then `videos.list` for full
  descriptions. One query per run (the brand), up to 10 videos. Item text = title + channel + description;
  no comments, captions or speech (the API only gives captions to a video's owner).
- Quota: each search costs 100 of the key's ~10,000 daily units (~100 searches/day), shared with NewsFact's
  supporting videos. Monitoring has its own budget, `MONITORING_YOUTUBE_DAILY_SEARCHES` (default 40, all
  workspaces together, in memory per UTC day). When it's used up, or Google reports the quota exceeded, the
  YouTube source shows as FAILED for that run with the reason; other sources still run.
- To grow: request a quota increase from Google (YouTube API Services audit), or give monitoring its own key.

## Billing: Paddle (recommended), PayPal (fallback)

Research summary (2026-10-14):
- **Stripe**: the Philippines isn't a supported merchant country (https://stripe.com/global). Not usable
  unless the business incorporates abroad.
- **Paddle**: merchant of record (collects and remits VAT/sales tax), sellers in the Philippines are not
  excluded (https://www.paddle.com/help/start/intro-to-paddle/which-countries-are-supported-by-paddle),
  individuals/sole traders can sell after domain + ID review. Fees 5% + $0.50 per transaction
  (https://www.paddle.com/pricing). **UNVERIFIED: payout method to a Philippine bank** — confirm with Paddle
  support before relying on it. Account approval requires a public site with pricing, terms and refund policy.
- **PayPal Subscriptions** (fallback): available in the Philippines; not a merchant of record (you handle tax);
  webhook verification is an API round trip.
- Lemon Squeezy (being folded into Stripe), PayMongo and Xendit (not MoR, USD support unverified) were rejected.

### What the code does (`intelligence/billing/`)

- `BillingProvider` interface; `PaddleBillingProvider` implements it with Paddle Billing's REST API:
  - Checkout: `POST /transactions` with the plan's `price_id` and `custom_data.organization_id` (set by the
    server, so the browser can't choose which workspace a payment unlocks). Returns `data.checkout.url`, which
    requires a **default payment link** configured in the Paddle dashboard.
  - Plan change: `PATCH /subscriptions/{id}` with `proration_billing_mode: prorated_immediately`.
  - Cancel: `POST /subscriptions/{id}/cancel` with `effective_from: next_billing_period`.
  - Customer portal (billing history, payment method): `POST /customers/{id}/portal-sessions`
    (endpoint shape UNVERIFIED against a live account).
- Webhook `POST /api/v2/billing/webhooks/paddle`:
  - Verifies `Paddle-Signature: ts=…;h1=…` as HMAC-SHA256(secret, `ts + ":" + raw body`), hex, constant-time
    compare, any of several `h1` values (secret rotation), and a replay window (default 300 s).
  - Idempotent: `billing_events (provider, event_id)`; a duplicate delivery returns `DUPLICATE`.
  - Out-of-order safe: an event older than the last applied one doesn't overwrite it.
  - Handles `subscription.*` events: `active` → ACTIVE, `trialing` → TRIALING, `past_due` → PAST_DUE (access
    continues while Paddle retries the payment), `canceled`/`paused` → CANCELED. A scheduled cancellation sets
    "cancels at period end".
  - The organisation comes from `custom_data.organization_id`, falling back to the stored subscription id
    (whether `subscription.*` payloads carry custom_data is UNVERIFIED; check in the sandbox).
- Paid features never unlock from the client: entitlements are read from `org_subscriptions`, which only
  verified webhooks (or the server-side trial) change.

### Setup steps (owner)

1. Create a Paddle **sandbox** account; create three monthly USD prices ($49, $149, $399).
2. In Paddle → Checkout settings, set a default payment link (an approved domain, e.g. the Pages site).
3. Create a notification destination pointing at `https://<backend>/api/v2/billing/webhooks/paddle` with the
   `subscription.*` events; copy its secret.
4. Set on Render: `PADDLE_API_BASE=https://sandbox-api.paddle.com`, `PADDLE_API_KEY`, `PADDLE_WEBHOOK_SECRET`,
   `PADDLE_PRICE_MONITOR`, `PADDLE_PRICE_INTELLIGENCE`, `PADDLE_PRICE_BUSINESS`.
5. Test a sandbox purchase end to end; confirm the webhook payload shape (custom_data on subscription events).
6. After seller approval: switch to live keys and `PADDLE_API_BASE=https://api.paddle.com`.

Until then the Subscription page says "Online payment isn't set up yet" and checkout returns 503.

## Scheduling

- In-process `@Scheduled` tick every minute dispatches due projects (`MonitoringScheduler`). Projects are
  claimed with a lease (`lease_until`) so two instances or a manual and a scheduled run never overlap.
- **Render's free plan sleeps when idle, and nothing runs while asleep.** Either run the backend on an
  always-on plan (`render.yaml` already says `starter`; confirm the actual plan), or configure an external
  cron (e.g. cron-job.org, GitHub Actions schedule, Render Cron Job) to call
  `POST /api/v2/internal/monitoring/tick` with header `X-Cron-Secret: $MONITORING_CRON_SECRET` every 5–15 min.
  The endpoint 404s unless the secret is configured, and only dispatches projects that are due.
- Missed checks are caught up on the next tick; the run records how late it started ("Started N h late…").
- Failed runs retry after 15, 30 and 60 minutes, then fall back to the normal interval; consecutive failures
  show as DEGRADED / FAILING health.

## Not built (documented gaps)

- Email: alert emails, invitation emails, scheduled report delivery. Needs a transactional email provider
  (e.g. Postmark, Resend, Amazon SES) and verified sender domain.
- Billing history inside the app (the Paddle customer portal covers it once connected).
- Social media connectors (see above).
