import { GoTrueClient, type AuthError } from "@supabase/auth-js";

/**
 * Supabase Auth (accounts across VeriFact, NewsFact, LegalFact and ResearchFact). Both values are public by
 * design; without them accounts are switched off and every product works signed out, as before.
 */
export const SUPABASE_URL = ((import.meta.env.VITE_SUPABASE_URL as string | undefined) ?? "").trim().replace(/\/+$/, "");
const PUBLISHABLE_KEY = ((import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY as string | undefined) ?? "").trim();

export const authEnabled = SUPABASE_URL !== "" && PUBLISHABLE_KEY !== "";

/** Also set as the minimum in Supabase (Authentication → Sign In / Providers → Email). */
export const MIN_PASSWORD = 8;

/**
 * PKCE flow: sign-in, confirmation and reset links come back with a one-time code that only this browser can
 * exchange. The session is kept in localStorage and refreshed automatically; access tokens are short-lived.
 */
export const auth: GoTrueClient | null = authEnabled
  ? new GoTrueClient({
      url: `${SUPABASE_URL}/auth/v1`,
      headers: { apikey: PUBLISHABLE_KEY },
      flowType: "pkce",
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: true,
      storageKey: "verifact.auth",
    })
  : null;

/** Which sign-in methods the project has switched on (Google only shows once it's configured). */
export async function enabledProviders(): Promise<{ google: boolean }> {
  if (!authEnabled) return { google: false };
  try {
    const response = await fetch(`${SUPABASE_URL}/auth/v1/settings`, { headers: { apikey: PUBLISHABLE_KEY } });
    if (!response.ok) return { google: false };
    const settings = (await response.json()) as { external?: Record<string, boolean> };
    return { google: settings.external?.google === true };
  } catch {
    return { google: false };
  }
}

/** Where auth emails and Google send people back to. */
export const callbackUrl = () => `${window.location.origin}/auth/callback`;
export const resetUrl = () => `${window.location.origin}/reset-password`;

const NEXT_KEY = "verifact.auth.next";

/** Only same-site paths, so a crafted link can't send someone elsewhere after signing in. */
export function safeNext(path: string | null | undefined): string {
  if (!path || !path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) return "/account";
  if (/^\/(signin|signup|auth\/callback|reset-password|forgot-password)\b/.test(path)) return "/account";
  return path;
}

export function rememberNext(path: string | null | undefined) {
  try {
    sessionStorage.setItem(NEXT_KEY, safeNext(path));
  } catch {
    // Private mode: fall back to the account page.
  }
}

export function takeNext(): string {
  try {
    const next = sessionStorage.getItem(NEXT_KEY);
    sessionStorage.removeItem(NEXT_KEY);
    return safeNext(next);
  } catch {
    return "/account";
  }
}

/**
 * User-facing text for Supabase Auth errors. Messages never reveal whether an email has an account:
 * sign-up and reset requests always get the same answer (see the pages).
 */
export function authErrorMessage(error: AuthError | Error | null | undefined): string {
  const code = error && "code" in error ? (error.code as string | undefined) : undefined;
  switch (code) {
    case "invalid_credentials":
      return "That email and password don't match. Check them, or reset your password.";
    case "email_not_confirmed":
      return "Please confirm your email first: open the link we sent you.";
    case "weak_password":
      return "Choose a stronger password: at least 8 characters, ideally a phrase that isn't used elsewhere.";
    case "same_password":
      return "That's your current password. Choose a new one.";
    case "over_email_send_rate_limit":
    case "over_request_rate_limit":
      return "Too many attempts for now. Please wait a few minutes and try again.";
    case "signup_disabled":
      return "New sign-ups are paused right now.";
    case "session_not_found":
    case "refresh_token_not_found":
      return "Your session has ended. Please sign in again.";
    default:
      return "Something went wrong. Please try again.";
  }
}
