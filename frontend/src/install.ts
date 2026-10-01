import { useSyncExternalStore } from "react";

/**
 * Installing VeriFact as an app (manifest.webmanifest). Chrome/Edge/Samsung Internet fire
 * `beforeinstallprompt` once, often before React renders, so this module listens from startup (imported by
 * main.tsx) and keeps the event for the Install button. Safari (iPhone) has no prompt: people use Share →
 * Add to Home Screen. In-app browsers (Messenger, Facebook, Instagram, TikTok…) can't install at all.
 */
interface InstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

let deferred: InstallPromptEvent | null = null;
let installedNow = false;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

if (typeof window !== "undefined") {
  window.addEventListener("beforeinstallprompt", (event) => {
    event.preventDefault(); // we show our own button instead of the mini-infobar
    deferred = event as InstallPromptEvent;
    notify();
  });
  window.addEventListener("appinstalled", () => {
    installedNow = true;
    deferred = null;
    notify();
  });
}

export type Platform = "android" | "ios" | "desktop";

export interface InstallState {
  platform: Platform;
  /** Inside a social app's built-in browser, which can't install apps. */
  inApp: boolean;
  /** Already running as the installed app, or installed during this visit. */
  installed: boolean;
  /** The browser offered its install prompt: the Install button can open it. */
  canPrompt: boolean;
}

const IN_APP = /FBAN|FBAV|FB_IAB|FBIOS|Messenger|Instagram|Line\/|MicroMessenger|Twitter|TikTok|BytedanceWebview|musical_ly|Snapchat|Viber|Telegram|GSA\//i;

function platformOf(ua: string): Platform {
  if (/iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return "ios";
  if (/Android/.test(ua)) return "android";
  return "desktop";
}

function standalone(): boolean {
  return (
    window.matchMedia("(display-mode: standalone)").matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  );
}

let cached: InstallState | null = null;
function snapshot(): InstallState {
  const ua = navigator.userAgent;
  const next: InstallState = {
    platform: platformOf(ua),
    inApp: IN_APP.test(ua),
    installed: installedNow || standalone(),
    canPrompt: deferred !== null,
  };
  // Same object while nothing changed, as useSyncExternalStore requires.
  if (
    cached &&
    cached.platform === next.platform &&
    cached.inApp === next.inApp &&
    cached.installed === next.installed &&
    cached.canPrompt === next.canPrompt
  ) {
    return cached;
  }
  cached = next;
  return next;
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useInstall(): InstallState {
  return useSyncExternalStore(subscribe, snapshot);
}

/** Opens the browser's install dialog. Resolves true if the person installed. */
export async function promptInstall(): Promise<boolean> {
  const event = deferred;
  if (!event) return false;
  deferred = null; // a prompt event can be used only once
  notify();
  await event.prompt();
  const { outcome } = await event.userChoice;
  return outcome === "accepted";
}

/**
 * Android: leaves an in-app browser for Chrome on the same page (Chrome's intent link). Elsewhere there's no
 * reliable way to switch browsers, so the page shows steps instead.
 */
export function openInChromeHref(path: string): string {
  return `intent://${window.location.host}${path}#Intent;scheme=https;package=com.android.chrome;end`;
}
