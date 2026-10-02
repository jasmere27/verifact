import { useSyncExternalStore } from "react";

/** Minimal pathname router: `/` (homepage), `/check` (VeriFact check), `/r/{id}` (shareable report), `/legal` (LegalFact), `/research` (ResearchFact, with `/research/w/{id}` workspaces and `/research/p/{id}` capstone projects), `/news`, `/privacy`, `/terms`, `/install`, `/about` (the full homepage; phones show a dashboard at `/`), and the account pages (`/signin`, `/signup`, `/forgot-password`, `/reset-password`, `/auth/callback`, `/account`). */
export type Route =
  | { name: "landing" }
  | { name: "check" }
  | { name: "report"; id: string }
  | { name: "legal" }
  | { name: "research" }
  | { name: "researchWorkspace"; id: string }
  | { name: "researchProject"; id: string }
  | { name: "news" }
  | { name: "newsWorkspace"; id: string }
  | { name: "signIn" }
  | { name: "signUp" }
  | { name: "forgotPassword" }
  | { name: "resetPassword" }
  | { name: "authCallback" }
  | { name: "account" }
  | { name: "privacy" }
  | { name: "install" }
  | { name: "about" }
  | { name: "terms" }
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
const RESEARCH_PROJECT_PATH = /^\/research\/p\/([0-9a-fA-F-]{36})\/?$/;
const NEWS_PATH = /^\/news\/([0-9a-fA-F-]{36})\/?$/;
const REPORT_PATH = /^\/r\/([A-Za-z0-9-]{1,64})\/?$/;

export function parseRoute(pathname: string): Route {
  if (pathname === "/" || pathname === "") return { name: "landing" };
  if (pathname === "/check" || pathname === "/check/") return { name: "check" };
  if (pathname === "/legal" || pathname === "/legal/") return { name: "legal" };
  if (pathname === "/research" || pathname === "/research/") return { name: "research" };
  if (pathname === "/privacy" || pathname === "/privacy/") return { name: "privacy" };
  if (pathname === "/install" || pathname === "/install/") return { name: "install" };
  if (pathname === "/about" || pathname === "/about/") return { name: "about" };
  if (pathname === "/terms" || pathname === "/terms/") return { name: "terms" };
  const workspace = RESEARCH_WORKSPACE_PATH.exec(pathname);
  if (workspace) return { name: "researchWorkspace", id: workspace[1] };
  const project = RESEARCH_PROJECT_PATH.exec(pathname);
  if (project) return { name: "researchProject", id: project[1] };
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
  const depth = historyDepth();
  if (options.replace) window.history.replaceState({ depth }, "", path);
  else window.history.pushState({ depth: depth + 1 }, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
  window.scrollTo(0, 0);
}

/** How many in-app pages are behind this one in the history (0 when the app was opened on this page). */
function historyDepth(): number {
  const state: unknown = window.history.state;
  const depth = state && typeof state === "object" ? (state as { depth?: unknown }).depth : undefined;
  return typeof depth === "number" ? depth : 0;
}

/** The tab-bar pages: they need no Back button. */
const TOP_LEVEL: ReadonlySet<Route["name"]> = new Set(["landing", "check", "research", "news"]);

/** Where Back goes when there's no in-app page behind this one, e.g. the app was opened from a shared link. */
function parentPath(route: Route["name"]): string | null {
  if (TOP_LEVEL.has(route)) return null;
  switch (route) {
    case "report":
      return "/check";
    case "researchWorkspace":
    case "researchProject":
      return "/research";
    case "newsWorkspace":
      return "/news";
    default:
      return "/";
  }
}

export const hasBack = (route: Route["name"]) => parentPath(route) !== null;

/** Back inside the app: the previous in-app page if there is one, otherwise this page's parent. */
export function goBack(route: Route["name"]) {
  if (historyDepth() > 0) window.history.back();
  else navigate(parentPath(route) ?? "/", { replace: true });
}
