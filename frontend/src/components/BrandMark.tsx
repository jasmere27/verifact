import { useId } from "react";

/**
 * The VeriFact mark: a gradient tile with a soft highlight and a "V" monogram that doubles as a check; its
 * bright stroke ends in a solid point, the evidence the verdict lands on. Ids are per instance so the header and footer marks don't collide.
 */
export function BrandMark({ size = 30 }: { size?: number }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg className="brand-mark" viewBox="0 0 32 32" width={size} height={size} aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={`${id}-bg`} x1="2" y1="2" x2="30" y2="30" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#4f46e5" />
          <stop offset="0.55" stopColor="#2563eb" />
          <stop offset="1" stopColor="#0d9488" />
        </linearGradient>
        <radialGradient id={`${id}-glow`} cx="9" cy="6" r="18" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.42" />
          <stop offset="1" stopColor="#ffffff" stopOpacity="0" />
        </radialGradient>
      </defs>
      <rect x="1" y="1" width="30" height="30" rx="9" fill={`url(#${id}-bg)`} />
      <rect x="1" y="1" width="30" height="30" rx="9" fill={`url(#${id}-glow)`} />
      <rect x="1.5" y="1.5" width="29" height="29" rx="8.5" fill="none" stroke="#ffffff" strokeOpacity="0.22" />
      <path d="M9 10l6.4 12.6" stroke="#ffffff" strokeOpacity="0.6" strokeWidth="3.3" strokeLinecap="round" />
      <path d="M15.4 22.6L22.3 9.9" stroke="#ffffff" strokeWidth="3.3" strokeLinecap="round" />
      <circle cx="23" cy="8.8" r="2.7" fill="#ffffff" />
    </svg>
  );
}

/** Mark + "VeriFact" wordmark ("Fact" carries the brand gradient). */
export default function Brand({ size = 30 }: { size?: number }) {
  return (
    <>
      <BrandMark size={size} />
      <span className="brand-word">
        Veri<span className="brand-word-accent">Fact</span>
      </span>
    </>
  );
}
