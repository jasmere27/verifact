import { useCallback, useState } from "react";

/**
 * Colour theme: follow the system, or force light/dark. The choice lives in localStorage and is applied as
 * `data-theme` on <html>; index.html applies it before first paint so there's no flash of the wrong theme.
 */
export type ThemeChoice = "system" | "light" | "dark";

const STORAGE_KEY = "verifact.theme";
const ORDER: ThemeChoice[] = ["system", "light", "dark"];
const THEME_COLORS = { light: "#f4f6fb", dark: "#0a0f1d" };

function readChoice(): ThemeChoice {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "system";
  } catch {
    return "system";
  }
}

function applyChoice(choice: ThemeChoice) {
  const root = document.documentElement;
  if (choice === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", choice);

  // Browser chrome colour: per-scheme metas when following the system, the forced colour otherwise.
  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach((meta) => {
    const scheme = meta.media.includes("dark") ? "dark" : "light";
    meta.content = THEME_COLORS[choice === "system" ? scheme : choice];
  });

  try {
    if (choice === "system") localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, choice);
  } catch {
    // Storage blocked (private mode etc.): the choice still applies for this page view.
  }
}

export function useTheme(): [ThemeChoice, () => void] {
  const [choice, setChoice] = useState<ThemeChoice>(readChoice);
  const cycle = useCallback(() => {
    setChoice((current) => {
      const next = ORDER[(ORDER.indexOf(current) + 1) % ORDER.length];
      applyChoice(next);
      return next;
    });
  }, []);
  return [choice, cycle];
}
