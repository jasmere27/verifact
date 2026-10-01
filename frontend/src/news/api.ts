import { API_BASE_URL, ApiError, streamResult } from "../api";
import type { StreamHandlers } from "../api";
import type { NewsReview, NewsWorkspace } from "./types";

const CHECK_TIMEOUT_MS = 170_000;
const TOKEN_KEY = (id: string) => `newsfact.token.${id}`;

const asArray = <T>(value: unknown): T[] => (Array.isArray(value) ? (value as T[]) : []);

function normalize(raw: unknown): NewsWorkspace {
  const w = (raw ?? {}) as NewsWorkspace;
  const check = w.check ?? ({} as NewsWorkspace["check"]);
  return {
    editToken: w.editToken ?? null,
    review: {
      decisions: w.review?.decisions ?? {},
      editorNote: w.review?.editorNote ?? null,
      updatedAt: w.review?.updatedAt ?? null,
    },
    check: {
      ...check,
      claims: asArray(check.claims),
      sources: asArray(check.sources),
      verdictCounts: check.verdictCounts ?? {},
      limitations: asArray(check.limitations),
      notice: check.notice || "Automated first pass for an editor. Confirm with primary sources.",
    },
  };
}

/** The edit token is kept only in this browser; without it a workspace is read-only. */
export function savedToken(id: string): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY(id));
  } catch {
    return null;
  }
}

function saveToken(id: string, token: string) {
  try {
    localStorage.setItem(TOKEN_KEY(id), token);
  } catch {
    // Storage blocked: the workspace stays editable for this page view only.
  }
}

function forgetToken(id: string) {
  try {
    localStorage.removeItem(TOKEN_KEY(id));
  } catch {
    // ignore
  }
}

export async function runNewsCheck(input: string, handlers: StreamHandlers, signal?: AbortSignal) {
  const w = await streamResult(
    "/api/v2/news/checks/stream",
    JSON.stringify({ input }),
    { "Content-Type": "application/json" },
    handlers,
    signal,
    normalize,
    CHECK_TIMEOUT_MS,
  );
  if (w.editToken && w.check.id) saveToken(w.check.id, w.editToken);
  return w;
}

export async function getNewsWorkspace(id: string, signal?: AbortSignal): Promise<NewsWorkspace> {
  const response = await fetch(`${API_BASE_URL}/api/v2/news/checks/${encodeURIComponent(id)}`, { signal });
  if (!response.ok) {
    throw new ApiError(
      response.status === 404 ? "That NewsFact review doesn't exist or is no longer available." : "Couldn't load the review.",
      response.status,
    );
  }
  return normalize(await response.json());
}

/** Deletes the check and its review; only with the edit token from creation. Already gone counts as done. */
export async function deleteNewsCheck(id: string, token: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/v2/news/checks/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { "X-Edit-Token": token },
  });
  if (!response.ok && response.status !== 404) {
    throw new ApiError(
      response.status === 403 ? "Only the person who ran this check can delete it." : "Couldn't delete the review. Please try again.",
      response.status,
    );
  }
  forgetToken(id);
}

export async function saveNewsReview(id: string, token: string, review: NewsReview): Promise<NewsReview> {
  const response = await fetch(`${API_BASE_URL}/api/v2/news/checks/${encodeURIComponent(id)}/review`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-Edit-Token": token },
    body: JSON.stringify(review),
  });
  if (!response.ok) {
    throw new ApiError(
      response.status === 403 ? "Only the person who ran this check can change its review." : "Couldn't save the review.",
      response.status,
    );
  }
  return (await response.json()) as NewsReview;
}
