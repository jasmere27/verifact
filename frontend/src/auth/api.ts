import { API_BASE_URL } from "../api";
import { auth } from "./client";

export type Role = "OWNER" | "ADMIN" | "MEMBER";

export interface OrganizationView {
  id: string;
  name: string;
  personal: boolean;
  role: Role;
}

export interface Account {
  id: string;
  email: string | null;
  displayName: string | null;
  organizations: OrganizationView[];
}

export class AccountApiError extends Error {
  readonly status: number;
  readonly requestId?: string;

  constructor(message: string, status: number, requestId?: string) {
    super(message);
    this.status = status;
    this.requestId = requestId;
  }
}

/** The signed-in user's access token (refreshed by the auth client when needed), or null when signed out. */
export async function accessToken(): Promise<string | null> {
  const { data } = (await auth?.getSession()) ?? { data: { session: null } };
  return data.session?.access_token ?? null;
}

/** After a 401: a fresh token, or null if the session can't be refreshed. */
export async function refreshedAccessToken(): Promise<string | null> {
  if (!auth) return null;
  const { data } = await auth.refreshSession();
  return data.session?.access_token ?? null;
}

async function token(): Promise<string> {
  const { data } = (await auth?.getSession()) ?? { data: { session: null } };
  if (!data.session) throw new AccountApiError("Please sign in to continue.", 401);
  return data.session.access_token;
}

/** Calls the API as the signed-in user; on a 401 refreshes the session once and retries. */
async function authed(path: string, init: RequestInit = {}): Promise<Response> {
  const send = (accessToken: string) =>
    fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers: { ...(init.headers as Record<string, string> | undefined), Authorization: `Bearer ${accessToken}` },
    });
  let response = await send(await token());
  if (response.status === 401 && auth) {
    const { data } = await auth.refreshSession();
    if (data.session) response = await send(data.session.access_token);
  }
  if (!response.ok) {
    let detail = "Something went wrong. Please try again.";
    let requestId: string | undefined;
    try {
      const problem = (await response.json()) as { detail?: string; requestId?: string };
      if (problem.detail) detail = problem.detail;
      requestId = problem.requestId;
    } catch {
      // Not JSON (e.g. a proxy error page).
    }
    throw new AccountApiError(detail, response.status, requestId);
  }
  return response;
}

export async function getAccount(): Promise<Account> {
  return (await authed("/api/v2/me")).json() as Promise<Account>;
}

export async function updateDisplayName(displayName: string): Promise<Account> {
  const response = await authed("/api/v2/me", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ displayName }),
  });
  return response.json() as Promise<Account>;
}

export async function deleteAccount(): Promise<void> {
  await authed("/api/v2/me", { method: "DELETE" });
}

/** An entry in "Your checks" (`GET /api/v2/me/checks`). `yours`: this account created the report and may delete it. */
export interface MyCheck {
  id: string;
  checkedAt: string;
  overallVerdict: string;
  label: string;
  yours: boolean;
}

export async function getMyChecks(): Promise<MyCheck[]> {
  return (await authed("/api/v2/me/checks")).json() as Promise<MyCheck[]>;
}

/** Removes a check from the history only; the report stays. */
export async function removeMyCheck(id: string): Promise<void> {
  await authed(`/api/v2/me/checks/${encodeURIComponent(id)}`, { method: "DELETE" });
}
