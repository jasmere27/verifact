import type { VerdictMeta } from "../verdicts";

/** Shape-distinct glyph per verdict so colour is never the only signal. Decorative: the label carries meaning. */
export default function VerdictIcon({ tone, size = 20 }: { tone: VerdictMeta["tone"]; size?: number }) {
  const common = {
    width: size,
    height: size,
    viewBox: "0 0 20 20",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.8,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
    focusable: false,
    className: "verdict-icon",
  };
  switch (tone) {
    case "supported":
      return (
        <svg {...common}>
          <circle cx="10" cy="10" r="8" />
          <path d="M6.3 10.3l2.5 2.5 5-5.4" />
        </svg>
      );
    case "partly":
      return (
        <svg {...common}>
          <circle cx="10" cy="10" r="8" />
          <path d="M10 2a8 8 0 0 0 0 16z" fill="currentColor" stroke="none" />
        </svg>
      );
    case "misleading":
      return (
        <svg {...common}>
          <path d="M10 2.6l8 14.2H2z" />
          <path d="M10 8v4" />
          <circle cx="10" cy="14.4" r="0.6" fill="currentColor" />
        </svg>
      );
    case "contradicted":
      return (
        <svg {...common}>
          <circle cx="10" cy="10" r="8" />
          <path d="M7 7l6 6M13 7l-6 6" />
        </svg>
      );
    case "mixed":
      return (
        <svg {...common}>
          <rect x="2.5" y="2.5" width="15" height="15" rx="3" />
          <path d="M6 7.5h8M6 12.5h4.5" />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <circle cx="10" cy="10" r="8" strokeDasharray="2.6 2.4" />
          <path d="M8 8a2 2 0 1 1 2.8 1.8c-.5.3-.8.7-.8 1.3v.4" />
          <circle cx="10" cy="14" r="0.6" fill="currentColor" />
        </svg>
      );
  }
}
