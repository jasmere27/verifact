import { useSyncExternalStore } from "react";

/** Minimal pathname router: `/` (homepage), `/check` (VeriFact check), `/r/{id}` (shareable report), `/legal` (LegalFact), `/research` (ResearchFact), `/news`, and the account pages (`/signin`, `/signup`, `/forgot-password`, `/reset-password`, `/auth/callback`, `/account`). */
export type Route =
  | { name: "landing" }
  | { name: "check" }
  | { name: "report"; id: string }
  | { name: "legal" }
  | { name: "research" }
  | { name: "researchWorkspace"; id: string }
  | { name: "news" }
  | { name: "newsWorkspace"; id: string }
  | { name: "signIn" }
  | { name: "signUp" }
  | { name: "forgotPassword" }
  | { name: "resetPassword" }
  | { name: "authCallback" }
  | { name: "account" }
  | { name: "notFound" };

const AUTH_ROUTES: Record<string, Route["name"]> = {
  "/signin": "signIn",
  "/signup": "signUp",
  "/forgot-password": "forgotPassword",
  "/reset-password": "resetPassword",
  "/auth/callback": "authCallback",
  "/account": "account",
};

const RESEARCH_WORKSPACE_PATH = /^\/research\/w\/([0-9a-fA-F-]{36})\/?$/;
const NEWS_PATH = /^\/news\/([0-9a-fA-F-]{36})\/?$/;
const REPORT_PATH = /^\/r\/([A-Za-z0-9-]{1,64})\/?$/;

export function parseRoute(pathname: string): Route {
  if (pathname === "/" || pathname === "") return { name: "landing" };
  if (pathname === "/check" || pathname === "/check/") return { name: "check" };
  if (pathname === "/legal" || pathname === "/legal/") return { name: "legal" };
  if (pathname === "/research" || pathname === "/research/") return { name: "research" };
  const workspace = RESEARCH_WORKSPACE_PATH.exec(pathname);
  if (workspace) return { name: "researchWorkspace", id: workspace[1] };
  if (pathname === "/news" || pathname === "/news/") return { name: "news" };
  const news = NEWS_PATH.exec(pathname);
  if (news) return { name: "newsWorkspace", id: news[1] };
  const authRoute = AUTH_ROUTES[pathname.replace(/\/+$/, "")];
  if (authRoute) return { name: authRoute } as Route;
  const match = REPORT_PATH.exec(pathname);
  if (match) return { name: "report", id: match[1] };
  return { name: "notFound" };
}

export const reportPath = (id: string) => `/r/${encodeURIComponent(id)}`;

function subscribe(onChange: () => void) {
  window.addEventListener("popstate", onChange);
  return () => window.removeEventListener("popstate", onChange);
}

const getPathname = () => window.location.pathname;

export function usePathname(): string {
  return useSyncExternalStore(subscribe, getPathname);
}

/**
 * Push a new history entry and notify subscribers (pushState doesn't fire popstate itself). {@code replace}
 * swaps the current entry instead, e.g. leaving a one-time sign-in link so Back doesn't return to it.
 */
export function navigate(path: string, options: { replace?: boolean } = {}) {
  if (path === window.location.pathname + window.location.search) return;
  if (options.replace) window.history.replaceState(null, "", path);
  else window.history.pushState(null, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
  window.scrollTo(0, 0);
}
