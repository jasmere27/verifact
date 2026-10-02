import { useSyncExternalStore } from "react";

/**
 * Phone layout: at or below this width the app switches to its mobile shell (bottom tab bar, compact header,
 * dashboard home). Keep in sync with the `760px` media queries in App.css.
 */
export const MOBILE_QUERY = "(max-width: 760px)";

function subscribe(onChange: () => void) {
  const media = window.matchMedia(MOBILE_QUERY);
  media.addEventListener("change", onChange);
  return () => media.removeEventListener("change", onChange);
}

export function useIsMobile(): boolean {
  return useSyncExternalStore(subscribe, () => window.matchMedia(MOBILE_QUERY).matches);
}

const TEXT_INPUT = /^(text|search|url|email|password|tel|number)$/;

function isTyping(el: Element | null): boolean {
  if (!el) return false;
  if (el instanceof HTMLTextAreaElement) return !el.readOnly;
  if (el instanceof HTMLInputElement) return TEXT_INPUT.test(el.type) && !el.readOnly;
  return el instanceof HTMLElement && el.isContentEditable;
}

function subscribeTyping(onChange: () => void) {
  // focusout fires before focus moves on; check on the next frame which element ended up focused.
  const later = () => requestAnimationFrame(onChange);
  document.addEventListener("focusin", onChange);
  document.addEventListener("focusout", later);
  return () => {
    document.removeEventListener("focusin", onChange);
    document.removeEventListener("focusout", later);
  };
}

/**
 * True while a text field has focus. On phones the on-screen keyboard is then open, so the tab bar steps aside
 * (otherwise Android lifts it above the keyboard, on top of what you're typing).
 */
export function useTyping(): boolean {
  return useSyncExternalStore(subscribeTyping, () => isTyping(document.activeElement));
}
