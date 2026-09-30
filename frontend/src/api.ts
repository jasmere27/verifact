import { toSourceType } from "./sources";
import type { SourcesFound, StageId, VerificationResult } from "./types";

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080").replace(/\/+$/, "");

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
    evidence: asArray<VerificationResult["evidence"][number]>(raw.evidence).map((e) => ({
      ...e,
      sourceType: toSourceType(e?.sourceType),
    })),
    limitations: asArray(raw.limitations),
    imageContext: raw.imageContext && typeof raw.imageContext === "object" ? raw.imageContext : null,
  };
}

/** A user-facing message (plus the request ID to quote) for any error from these calls. */
export function errorMessage(err: unknown): { message: string; requestId?: string } {
  if (err instanceof ApiError) {
    const wait =
      err.status === 429 && err.retryAfterSeconds ? ` You can try again in about ${err.retryAfterSeconds} seconds.` : "";
    return { message: `${err.message}${wait}`, requestId: err.requestId };
  }
  return { message: "Something went wrong. Please try again." };
}

const TIMEOUT_MESSAGE = "The check took too long to complete. Please try again in a moment.";
const OFFLINE_MESSAGE = "Couldn't reach VeriFact. Check your connection and try again.";

/**
 * Run `work` with an overall timeout (default 120 s, covering the whole response, streamed or not).
 * If the caller's `signal` aborts, the AbortError is rethrown unchanged so callers can ignore it;
 * network failures and timeouts become ApiErrors.
 */
async function withTimeout<T>(
  signal: AbortSignal | undefined,
  work: (signal: AbortSignal) => Promise<T>,
  timeoutMs = REQUEST_TIMEOUT_MS,
): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const onCallerAbort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener("abort", onCallerAbort);
  try {
    return await work(controller.signal);
  } catch (err) {
    if (err instanceof ApiError) throw err;
    if (timedOut) throw new ApiError(TIMEOUT_MESSAGE, 0);
    if (isAbortError(err) || signal?.aborted) throw err;
    throw new ApiError(OFFLINE_MESSAGE, 0);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", onCallerAbort);
  }
}

function requestResult(path: string, init: RequestInit, signal?: AbortSignal): Promise<VerificationResult> {
  return withTimeout(signal, async (timeoutSignal) => {
    const response = await fetch(`${API_BASE_URL}${path}`, { ...init, signal: timeoutSignal });
    if (!response.ok) throw await toApiError(response);
    return normalize((await response.json()) as VerificationResult);
  });
}

export function getVerification(id: string, signal?: AbortSignal): Promise<VerificationResult> {
  return requestResult(`/api/v2/verifications/${encodeURIComponent(id)}`, { method: "GET" }, signal);
}

/* ---------- Streaming checks (Server-Sent Events over a POST response) ---------- */

export interface SseFrame {
  event: string;
  data: string;
}

/**
 * Incremental SSE parser. Network chunks can split anywhere — mid-line, mid-frame, even mid
 * UTF-8 character — so bytes are decoded in streaming mode and text is buffered until a blank
 * line completes a frame. Handles LF, CRLF and CR line endings, comment lines, and multi-line data.
 */
export function createSseParser(onFrame: (frame: SseFrame) => void) {
  const decoder = new TextDecoder();
  let buffer = "";

  function dispatch(raw: string) {
    let event = "message";
    const data: string[] = [];
    for (const line of raw.split("\n")) {
      if (!line || line.startsWith(":")) continue;
      const colon = line.indexOf(":");
      const field = colon === -1 ? line : line.slice(0, colon);
      let value = colon === -1 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "event") event = value;
      else if (field === "data") data.push(value);
    }
    if (data.length) onFrame({ event, data: data.join("\n") });
  }

  function drain(final: boolean) {
    // A trailing "\r" may be the first half of a "\r\n" split across chunks: hold it back until the next chunk.
    const held = !final && buffer.endsWith("\r") ? "\r" : "";
    if (held) buffer = buffer.slice(0, -1);
    buffer = buffer.replace(/\r\n?/g, "\n");
    let end: number;
    while ((end = buffer.indexOf("\n\n")) !== -1) {
      dispatch(buffer.slice(0, end));
      buffer = buffer.slice(end + 2);
    }
    buffer += held;
  }

  return {
    push(chunk: Uint8Array) {
      buffer += decoder.decode(chunk, { stream: true });
      drain(false);
    },
    end() {
      buffer += decoder.decode();
      drain(true);
      if (buffer.trim()) dispatch(buffer);
      buffer = "";
    },
  };
}

export interface StreamHandlers {
  onStage?: (stage: StageId) => void;
  onClaims?: (claims: string[]) => void;
  onSources?: (sources: SourcesFound) => void;
}

const STAGES: readonly StageId[] = ["READING_INPUT", "EXTRACTING_CLAIMS", "SEARCHING", "ASSESSING"];

function parseJson(data: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(data);
    return value && typeof value === "object" ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/**
 * POST and read a Server-Sent Events stream (`stage`, `claims`, `sources`, then `result` or `error`).
 * Shared by VeriFact checks and LegalFact case intelligence; `toResult` validates the result payload.
 */
export async function streamResult<T = VerificationResult>(
  path: string,
  body: BodyInit,
  headers: HeadersInit,
  handlers: StreamHandlers,
  signal?: AbortSignal,
  toResult: (raw: unknown) => T = (raw) => normalize(raw as VerificationResult) as T,
  timeoutMs = REQUEST_TIMEOUT_MS,
): Promise<T> {
  return withTimeout(signal, async (timeoutSignal) => {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      method: "POST",
      headers: { Accept: "text/event-stream", ...headers },
      body,
      signal: timeoutSignal,
    });
    if (!response.ok) throw await toApiError(response);

    const requestId = response.headers.get("X-Request-Id") ?? undefined;
    // A proxy or old backend may answer with plain JSON; accept that too.
    if (!response.headers.get("Content-Type")?.includes("text/event-stream")) {
      return toResult(await response.json());
    }
    if (!response.body) throw new ApiError(OFFLINE_MESSAGE, 0, requestId);

    let outcome: { result: T } | { error: ApiError } | null = null;
    const parser = createSseParser(({ event, data }) => {
      if (outcome) return;
      const payload = parseJson(data);
      if (!payload) return;
      switch (event) {
        case "stage":
          if (STAGES.includes(payload.stage as StageId)) handlers.onStage?.(payload.stage as StageId);
          break;
        case "claims":
          handlers.onClaims?.(asArray<unknown>(payload.claims).filter((c): c is string => typeof c === "string"));
          break;
        case "sources":
          handlers.onSources?.({
            count: typeof payload.count === "number" && payload.count >= 0 ? payload.count : 0,
            domains: asArray<unknown>(payload.domains).filter((d): d is string => typeof d === "string"),
          });
          break;
        case "result":
          outcome = { result: toResult(payload) };
          break;
        case "error": {
          const status = typeof payload.status === "number" ? payload.status : 500;
          const detail = typeof payload.detail === "string" && payload.detail.trim() ? payload.detail : undefined;
          outcome = {
            error: new ApiError(
              detail ?? FALLBACK_MESSAGES[status] ?? "Something went wrong while checking. Please try again.",
              status,
              typeof payload.requestId === "string" ? payload.requestId : requestId,
            ),
          };
          break;
        }
      }
    });

    const reader = response.body.getReader();
    try {
      while (!outcome) {
        const { done, value } = await reader.read();
        if (done) {
          parser.end();
          break;
        }
        parser.push(value);
      }
    } finally {
      if (outcome) reader.cancel().catch(() => undefined);
    }

    const final = outcome as { result: T } | { error: ApiError } | null;
    if (!final) {
      throw new ApiError("The check stopped before it finished. Please try again.", 0, requestId);
    }
    if ("error" in final) throw final.error;
    return final.result;
  }, timeoutMs);
}

/** `refresh: true` asks the server to run a new check instead of reusing a recent report for the same input. */
export function verifyTextStream(input: string, handlers: StreamHandlers, signal?: AbortSignal, refresh = false) {
  return streamResult(
    "/api/v2/verifications/stream",
    JSON.stringify(refresh ? { input, refresh: true } : { input }),
    { "Content-Type": "application/json" },
    handlers,
    signal,
  );
}

export function verifyFileStream(kind: "image" | "audio", file: File, handlers: StreamHandlers, signal?: AbortSignal) {
  const formData = new FormData();
  formData.append("file", file);
  return streamResult(`/api/v2/verifications/${kind}/stream`, formData, {}, handlers, signal);
}

/* ---------- Feedback ---------- */

export type FeedbackReason = "WRONG_VERDICT" | "BAD_SOURCES" | "MISSED_CLAIM" | "OTHER";

export interface FeedbackBody {
  helpful: boolean;
  reason: FeedbackReason | null;
  /** At most 500 characters. */
  comment: string | null;
}

/** `POST /api/v2/verifications/{id}/feedback` → 204. Errors (400/404/429) are problem+json. */
export function sendFeedback(id: string, body: FeedbackBody, signal?: AbortSignal): Promise<void> {
  return withTimeout(signal, async (timeoutSignal) => {
    const response = await fetch(`${API_BASE_URL}/api/v2/verifications/${encodeURIComponent(id)}/feedback`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: timeoutSignal,
    });
    if (!response.ok) throw await toApiError(response);
  });
}
