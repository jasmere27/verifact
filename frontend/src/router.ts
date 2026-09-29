import { useSyncExternalStore } from "react";

/** Minimal pathname router: `/` (check) and `/r/{id}` (shareable report). */
export type Route = { name: "home" } | { name: "report"; id: string } | { name: "notFound" };

const REPORT_PATH = /^\/r\/([A-Za-z0-9-]{1,64})\/?$/;

export function parseRoute(pathname: string): Route {
  if (pathname === "/" || pathname === "") return { name: "home" };
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
