import { API_BASE_URL, ApiError } from "../../api";
import type { Category, Discovery, Folder, FoundSource, Workspace } from "./types";

const TOKEN_KEY = (id: string) => `researchfact.token.${id}`;

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
    // Storage blocked: editable for this page view only.
  }
}

function forgetToken(id: string) {
  try {
    localStorage.removeItem(TOKEN_KEY(id));
  } catch {
    // ignore
  }
}

async function call<T>(path: string, init: RequestInit, timeoutMs = 120_000): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...init, signal: controller.signal });
  } catch {
    throw new ApiError(controller.signal.aborted ? "That took too long. Please try again." : "Couldn't reach ResearchFact.", 0);
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) {
    let detail = "";
    try {
      detail = ((await response.json()) as { detail?: string }).detail ?? "";
    } catch {
      // not JSON
    }
    throw new ApiError(detail || "Something went wrong. Please try again.", response.status, response.headers.get("X-Request-Id") ?? undefined);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

const json = (body: unknown, token?: string | null): RequestInit => ({
  headers: { "Content-Type": "application/json", ...(token ? { "X-Edit-Token": token } : {}) },
  body: JSON.stringify(body),
});

export async function createWorkspace(topic: string, field: string, country: string | null) {
  const v = await call<{ workspace: Workspace; editToken: string }>("/api/v2/research/workspaces", {
    method: "POST",
    ...json({ topic, field: field || null, country }),
  });
  saveToken(v.workspace.id, v.editToken);
  return v.workspace;
}

export async function getWorkspace(id: string) {
  const v = await call<{ workspace: Workspace }>(`/api/v2/research/workspaces/${encodeURIComponent(id)}`, { method: "GET" });
  return { ...v.workspace, sources: v.workspace.sources ?? [] };
}

export function updateWorkspace(
  id: string,
  token: string,
  changes: { topic?: string; notes?: string | null; sources?: { key: string; folder: Folder; studentNote: string | null }[] },
) {
  return call<Workspace>(`/api/v2/research/workspaces/${encodeURIComponent(id)}`, { method: "PUT", ...json(changes, token) });
}

export function addSource(id: string, token: string, source: FoundSource, folder: Folder) {
  return call<Workspace>(`/api/v2/research/workspaces/${encodeURIComponent(id)}/sources`, {
    method: "POST",
    ...json({ key: source.key, folder, relevance: source.relevance, relevanceQuote: source.relevanceQuote, stance: source.stance }, token),
  });
}

export async function deleteWorkspace(id: string, token: string) {
  await call<void>(`/api/v2/research/workspaces/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { "X-Edit-Token": token },
  });
  forgetToken(id);
}

export function discover(topic: string, category: Category, text: string | null, country: string | null) {
  return call<Discovery>("/api/v2/research/discover", { method: "POST", ...json({ topic, category, text, country }) }, 150_000);
}
