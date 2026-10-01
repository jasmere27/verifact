import { useTheme } from "../theme";
import type { Theme } from "../theme";

const LABELS: Record<Theme, string> = { light: "Light", dark: "Dark" };

function ThemeIcon({ theme }: { theme: Theme }) {
  const common = {
    width: 18,
    height: 18,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.8,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
    focusable: false,
  };
  if (theme === "light") {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      <path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z" />
    </svg>
  );
}

/** Header button switching between Light and Dark. */
export default function ThemeToggle() {
  const [theme, toggle] = useTheme();
  const next = theme === "dark" ? "light" : "dark";
  return (
    <button
      type="button"
      className="theme-toggle"
      onClick={toggle}
      aria-label={`${LABELS[theme]} mode. Switch to ${LABELS[next].toLowerCase()} mode`}
      title={`Switch to ${LABELS[next].toLowerCase()} mode`}
    >
      <ThemeIcon theme={theme} />
      <span className="theme-toggle-label">{LABELS[theme]}</span>
    </button>
  );
}
