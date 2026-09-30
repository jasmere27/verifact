import { useTheme } from "../theme";
import type { ThemeChoice } from "../theme";

const LABELS: Record<ThemeChoice, string> = { system: "System", light: "Light", dark: "Dark" };
const NEXT: Record<ThemeChoice, ThemeChoice> = { system: "light", light: "dark", dark: "system" };

function ThemeIcon({ choice }: { choice: ThemeChoice }) {
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
  if (choice === "light") {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
      </svg>
    );
  }
  if (choice === "dark") {
    return (
      <svg {...common}>
        <path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      <rect x="3" y="4" width="18" height="12" rx="2" />
      <path d="M8 20h8M12 16v4" />
    </svg>
  );
}

/** Header button cycling System → Light → Dark. */
export default function ThemeToggle() {
  const [choice, cycle] = useTheme();
  return (
    <button
      type="button"
      className="theme-toggle"
      onClick={cycle}
      aria-label={`Theme: ${LABELS[choice]}. Switch to ${LABELS[NEXT[choice]]}`}
      title={`Theme: ${LABELS[choice]}`}
    >
      <ThemeIcon choice={choice} />
      <span className="theme-toggle-label">{LABELS[choice]}</span>
    </button>
  );
}
