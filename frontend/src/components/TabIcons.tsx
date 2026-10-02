/**
 * Tab bar icons on one 24px grid with the same stroke. `active` draws the filled variant (the current tab);
 * inner details are then cut out in the surface colour so they stay readable on the filled shape.
 */
export type TabIconName = "home" | "check" | "research" | "news" | "more";

const CUT = "var(--surface)";

export default function TabIcon({ name, active }: { name: TabIconName; active: boolean }) {
  const fill = active ? "currentColor" : "none";
  return (
    <svg
      className="tab-icon"
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.9"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {name === "home" && (
        <>
          <path d="M3.5 10.2 12 3.5l8.5 6.7v9.3a1.5 1.5 0 0 1-1.5 1.5h-4v-5.5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1V21H5a1.5 1.5 0 0 1-1.5-1.5z" fill={fill} />
        </>
      )}
      {name === "check" && (
        <>
          <path d="M12 2.8 4.8 5.6v5.9c0 4.5 3 8.4 7.2 9.7 4.2-1.3 7.2-5.2 7.2-9.7V5.6z" fill={fill} />
          <path d="m8.7 12.1 2.3 2.3 4.4-4.6" stroke={active ? CUT : "currentColor"} strokeWidth="2.1" />
        </>
      )}
      {name === "research" && (
        <>
          <path d="M2.5 9 12 4.5 21.5 9 12 13.5z" fill={fill} />
          <path d="M6.5 11.2v4.6c0 1.6 2.5 3.2 5.5 3.2s5.5-1.6 5.5-3.2v-4.6" />
          <path d="M21.5 9v5.5" />
        </>
      )}
      {name === "news" && (
        <>
          <path d="M4.5 4.5h11.5v14.8a1.7 1.7 0 0 0 1.7 1.7H6.2a1.7 1.7 0 0 1-1.7-1.7z" fill={fill} />
          <path d="M16 8.5h3.5v10.8a1.7 1.7 0 0 1-3.4 0" />
          <path d="M8 8.5h4.5M8 12h4.5M8 15.5h2.5" stroke={active ? CUT : "currentColor"} />
        </>
      )}
      {name === "more" && (
        <>
          <rect x="4" y="4" width="6.5" height="6.5" rx="1.8" fill={fill} />
          <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.8" fill={fill} />
          <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.8" fill={fill} />
          <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.8" fill={fill} />
        </>
      )}
    </svg>
  );
}
