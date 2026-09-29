/**
 * Cloudflare Pages Function for `/r/{id}`: link previews for shared reports.
 *
 * Social crawlers (Facebook, Messenger, X, Slack…) don't run JavaScript, so the SPA's generic tags are all they
 * would see. This fetches the report from the API and rewrites the Open Graph / Twitter tags already present in
 * index.html. Any problem (bad id, API asleep or slow, unknown report) falls through to the unchanged SPA.
 *
 * Not type-checked by the app build (tsconfig.app.json only includes src/); wrangler bundles it with esbuild.
 * `functions/tsconfig.json` type-checks it against Workers-style globals: `npx tsc -p functions`.
 */

/* ---------- Minimal Workers types (no @cloudflare/workers-types dependency) ---------- */

interface Env {
  API_BASE_URL?: string;
  ASSETS: { fetch(request: Request | URL | string): Promise<Response> };
}

type Ctx = {
  request: Request;
  env: Env;
  params: { id: string };
  next(): Promise<Response>;
};

interface RewriterElement {
  setAttribute(name: string, value: string): RewriterElement;
  /** Text is HTML-escaped unless `{ html: true }` is passed (never done here). */
  setInnerContent(content: string, options?: { html?: boolean }): RewriterElement;
}

declare class HTMLRewriter {
  on(selector: string, handlers: { element?(element: RewriterElement): void }): HTMLRewriter;
  transform(response: Response): Response;
}

/* ---------- Report → preview text ---------- */

const DEFAULT_API = "https://verifact-backend-5mux.onrender.com";
const ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;
const API_TIMEOUT_MS = 4000;

const LABELS: Record<string, string> = {
  SUPPORTED: "Supported",
  PARTLY_SUPPORTED: "Partly supported",
  MISLEADING: "Misleading",
  CONTRADICTED: "Contradicted",
  INSUFFICIENT_EVIDENCE: "Not enough evidence",
  MIXED: "Mixed results",
};

interface ReportSummary {
  overallVerdict: string;
  summary: string;
  claims: string[];
}

function excerpt(text: string, max: number): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max - 1).trimEnd()}…` : flat;
}

/** Pull out only the fields we need, tolerating a partial or unexpected payload. */
function toSummary(value: unknown): ReportSummary | null {
  if (!value || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  if (typeof v.overallVerdict !== "string" || !(v.overallVerdict in LABELS)) return null;
  const claims = Array.isArray(v.claims)
    ? v.claims
        .map((c) => (c && typeof c === "object" ? (c as Record<string, unknown>).text : undefined))
        .filter((t): t is string => typeof t === "string" && t.trim() !== "")
    : [];
  return { overallVerdict: v.overallVerdict, summary: typeof v.summary === "string" ? v.summary : "", claims };
}

async function fetchReport(apiBase: string, id: string): Promise<ReportSummary | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), API_TIMEOUT_MS);
  try {
    const response = await fetch(`${apiBase}/api/v2/verifications/${id}`, {
      headers: { Accept: "application/json" },
      signal: controller.signal,
    });
    if (response.status !== 200) return null;
    return toSummary(await response.json());
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/* ---------- Handler ---------- */

export async function onRequestGet(context: Ctx): Promise<Response> {
  const { request, env, params } = context;
  const id = typeof params.id === "string" ? params.id : "";
  if (!ID_PATTERN.test(id)) return context.next();

  const apiBase = (env.API_BASE_URL || DEFAULT_API).replace(/\/+$/, "");
  const report = await fetchReport(apiBase, id);
  if (!report) return context.next();

  const url = new URL(request.url);
  const origin = `https://${url.host}`;
  // "/" rather than "/index.html": Pages redirects the latter to the former.
  const shell = await env.ASSETS.fetch(new URL("/", request.url));
  if (!shell.ok) return context.next();

  const label = LABELS[report.overallVerdict];
  const title =
    report.overallVerdict === "MIXED"
      ? `${label}: ${report.claims.length} claims checked`
      : report.claims.length
        ? `${label}: “${excerpt(report.claims[0], 110)}”`
        : `${label} — VeriFact check`;
  const description = excerpt(report.summary, 200) || "See the sources VeriFact found for this claim.";
  const canonical = `${origin}/r/${id}`;
  const image = `${origin}/og/${report.overallVerdict.toLowerCase()}.png`;
  const imageAlt = `VeriFact verdict: ${label}`;

  const content = (value: string) => ({
    element(element: RewriterElement) {
      element.setAttribute("content", value);
    },
  });

  const response = new Response(shell.body, shell);
  response.headers.set("Cache-Control", "public, max-age=300");
  response.headers.delete("ETag");

  return new HTMLRewriter()
    .on("title", {
      element(element) {
        element.setInnerContent(`${title} · VeriFact`);
      },
    })
    .on('meta[name="description"]', content(description))
    .on('meta[property="og:type"]', content("article"))
    .on('meta[property="og:site_name"]', content("VeriFact"))
    .on('meta[property="og:title"]', content(title))
    .on('meta[property="og:description"]', content(description))
    .on('meta[property="og:url"]', content(canonical))
    .on('meta[property="og:image"]', content(image))
    .on('meta[property="og:image:width"]', content("1200"))
    .on('meta[property="og:image:height"]', content("630"))
    .on('meta[property="og:image:alt"]', content(imageAlt))
    .on('meta[name="twitter:card"]', content("summary_large_image"))
    .on('meta[name="twitter:title"]', content(title))
    .on('meta[name="twitter:description"]', content(description))
    .on('meta[name="twitter:image"]', content(image))
    .transform(response);
}
