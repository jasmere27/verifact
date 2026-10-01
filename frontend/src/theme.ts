import { useCallback, useEffect, useState } from "react";

/**
 * Colour theme: Light or Dark. Until the visitor picks one, the page follows the device setting (no
 * `data-theme`, CSS media queries apply) and the toggle shows whichever that is. A pick is stored in
 * localStorage and applied as `data-theme` on <html>; public/theme-init.js applies it before first paint.
 */
export type Theme = "light" | "dark";

const STORAGE_KEY = "verifact.theme";
const THEME_COLORS = { light: "#f4f6fb", dark: "#0a0f1d" };
const DARK_QUERY = "(prefers-color-scheme: dark)";

function stored(): Theme | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === "light" || value === "dark" ? value : null;
  } catch {
    return null;
  }
}

const deviceTheme = (): Theme => (window.matchMedia(DARK_QUERY).matches ? "dark" : "light");

function apply(theme: Theme) {
  document.documentElement.setAttribute("data-theme", theme);
  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach((meta) => {
    meta.content = THEME_COLORS[theme];
  });
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // Storage blocked (private mode etc.): the choice still applies for this page view.
  }
}

export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => stored() ?? deviceTheme());

  // Not chosen yet: keep showing the device's theme if it changes (e.g. automatic dark mode at night).
  useEffect(() => {
    if (stored()) return;
    const media = window.matchMedia(DARK_QUERY);
    const follow = () => {
      if (!stored()) setTheme(media.matches ? "dark" : "light");
    };
    media.addEventListener("change", follow);
    return () => media.removeEventListener("change", follow);
  }, []);

  const toggle = useCallback(() => {
    setTheme((current) => {
      const next: Theme = current === "dark" ? "light" : "dark";
      apply(next);
      return next;
    });
  }, []);
  return [theme, toggle];
}
