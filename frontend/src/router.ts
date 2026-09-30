import { useSyncExternalStore } from "react";

/** Minimal pathname router: `/` (homepage), `/check` (VeriFact check), `/r/{id}` (shareable report), `/legal` (LegalFact), `/research` (ResearchFact). */
export type Route =
  | { name: "landing" }
  | { name: "check" }
  | { name: "report"; id: string }
  | { name: "legal" }
  | { name: "research" }
  | { name: "researchWorkspace"; id: string }
  | { name: "news" }
  | { name: "newsWorkspace"; id: string }
  | { name: "notFound" };

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

/** Push a new history entry and notify subscribers (pushState doesn't fire popstate itself). */
export function navigate(path: string) {
  if (path === window.location.pathname) return;
  window.history.pushState(null, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
  window.scrollTo(0, 0);
}
