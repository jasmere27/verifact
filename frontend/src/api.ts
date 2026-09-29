import type { HistoryPage } from "./types";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

/** Error carrying the HTTP status and the server's user-facing message. */
export class ApiError extends Error {
  readonly status: number;
  readonly requestId?: string;

  constructor(message: string, status: number, requestId?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.requestId = requestId;
  }
}

const FALLBACK_MESSAGES: Record<number, string> = {
  413: "That upload or text is too large.",
  429: "You're sending checks too quickly. Please wait a moment and try again.",
  502: "The analysis service is unavailable right now. Please try again shortly.",
  503: "VeriFact is temporarily unavailable. Please try again later.",
};

/** Errors are RFC 9457 problem+json; show its `detail`, never raw bodies or stack traces. */
async function toApiError(response: Response): Promise<ApiError> {
  const requestId = response.headers.get("X-Request-Id") ?? undefined;
  let detail: string | undefined;
  if (response.headers.get("Content-Type")?.includes("json")) {
    try {
      const body = (await response.json()) as { detail?: unknown };
      if (typeof body.detail === "string") detail = body.detail;
    } catch {
      // fall through to the generic message
    }
  }
  const message =
    detail ?? FALLBACK_MESSAGES[response.status] ?? `Request failed (status ${response.status}). Please try again.`;
  return new ApiError(message, response.status, requestId);
}

async function handleTextResponse(response: Response): Promise<string> {
  if (!response.ok) {
    throw await toApiError(response);
  }
  return response.text();
}

export async function checkText(news: string): Promise<string> {
  const response = await fetch(`${API_BASE_URL}/api/v1/isFakeNews`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ news }),
  });
  return handleTextResponse(response);
}

export async function checkImage(file: File): Promise<string> {
  const formData = new FormData();
  formData.append("file", file);
  const response = await fetch(`${API_BASE_URL}/api/v1/analyzeImage`, {
    method: "POST",
    body: formData,
  });
  return handleTextResponse(response);
}

export async function checkAudio(file: File): Promise<string> {
  const formData = new FormData();
  formData.append("file", file);
  const response = await fetch(`${API_BASE_URL}/api/v1/analyzeAudio`, {
    method: "POST",
    body: formData,
  });
  return handleTextResponse(response);
}

export async function fetchHistory(page: number, size = 10): Promise<HistoryPage> {
  const response = await fetch(`${API_BASE_URL}/api/v1/history?page=${page}&size=${size}`);
  if (!response.ok) {
    throw await toApiError(response);
  }
  return response.json();
}
