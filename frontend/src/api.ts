import type { VerificationResult } from "./types";

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080").replace(/\/+$/, "");

/** Checks run two AI calls plus web searches (~10–40 s); leave generous headroom. */
const REQUEST_TIMEOUT_MS = 120_000;

/** Error carrying the HTTP status and the server's user-facing message. */
export class ApiError extends Error {
  readonly status: number;
  readonly requestId?: string;
  /** Seconds to wait before retrying, from `Retry-After` (429/503). */
  readonly retryAfterSeconds?: number;

  constructor(message: string, status: number, requestId?: string, retryAfterSeconds?: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.requestId = requestId;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

const FALLBACK_MESSAGES: Record<number, string> = {
  400: "That request couldn't be processed. Please check your input and try again.",
  404: "We couldn't find that report.",
  413: "That upload or text is too large.",
  415: "That file type isn't supported.",
  422: "We couldn't find a specific factual claim to check.",
  429: "You're sending checks too quickly. Please wait a moment and try again.",
  502: "The analysis service is unavailable right now. Please try again shortly.",
  503: "VeriFact is temporarily unavailable. Please try again later.",
};

function parseRetryAfter(value: string | null): number | undefined {
  if (!value) return undefined;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.ceil(seconds);
  const date = Date.parse(value);
  if (!Number.isNaN(date)) return Math.max(0, Math.ceil((date - Date.now()) / 1000));
  return undefined;
}

/** Errors are RFC 9457 problem+json; show its `detail`, never raw bodies or stack traces. */
async function toApiError(response: Response): Promise<ApiError> {
  const requestId = response.headers.get("X-Request-Id") ?? undefined;
  let detail: string | undefined;
  if (response.headers.get("Content-Type")?.includes("json")) {
    try {
      const body = (await response.json()) as { detail?: unknown };
      if (typeof body.detail === "string" && body.detail.trim()) detail = body.detail;
    } catch {
      // fall through to the generic message
    }
  }
  const message =
    detail ?? FALLBACK_MESSAGES[response.status] ?? `Request failed (status ${response.status}). Please try again.`;
  return new ApiError(message, response.status, requestId, parseRetryAfter(response.headers.get("Retry-After")));
}

function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

const asArray = <T>(value: unknown): T[] => (Array.isArray(value) ? (value as T[]) : []);

/** Defensive: guarantee the array fields exist so rendering never crashes on a partial payload. */
function normalize(raw: VerificationResult): VerificationResult {
  return {
    ...raw,
    claims: asArray<VerificationResult["claims"][number]>(raw.claims).map((c) => ({
      ...c,
      supportingEvidenceIds: asArray<string>(c.supportingEvidenceIds),
      contradictingEvidenceIds: asArray<string>(c.contradictingEvidenceIds),
    })),
    evidence: asArray(raw.evidence),
    limitations: asArray(raw.limitations),
  };
}

/**
 * Fetch JSON with a timeout. If the caller's `signal` aborts, the AbortError is rethrown
 * unchanged so callers can ignore it; network failures and timeouts become ApiErrors.
 */
async function requestResult(path: string, init: RequestInit, signal?: AbortSignal): Promise<VerificationResult> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  const onCallerAbort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener("abort", onCallerAbort);

  try {
    let response: Response;
    try {
      response = await fetch(`${API_BASE_URL}${path}`, { ...init, signal: controller.signal });
    } catch (err) {
      if (isAbortError(err) && !timedOut) throw err;
      if (timedOut) {
        throw new ApiError("The check took too long to complete. Please try again in a moment.", 0);
      }
      throw new ApiError("Couldn't reach VeriFact. Check your connection and try again.", 0);
    }
    if (!response.ok) throw await toApiError(response);
    return normalize((await response.json()) as VerificationResult);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", onCallerAbort);
  }
}

export function verifyText(input: string, signal?: AbortSignal): Promise<VerificationResult> {
  return requestResult(
    "/api/v2/verifications",
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ input }) },
    signal,
  );
}

function verifyFile(kind: "image" | "audio", file: File, signal?: AbortSignal): Promise<VerificationResult> {
  const formData = new FormData();
  formData.append("file", file);
  return requestResult(`/api/v2/verifications/${kind}`, { method: "POST", body: formData }, signal);
}

export const verifyImage = (file: File, signal?: AbortSignal) => verifyFile("image", file, signal);
export const verifyAudio = (file: File, signal?: AbortSignal) => verifyFile("audio", file, signal);

export function getVerification(id: string, signal?: AbortSignal): Promise<VerificationResult> {
  return requestResult(`/api/v2/verifications/${encodeURIComponent(id)}`, { method: "GET" }, signal);
}
