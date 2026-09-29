import type { Evidence, SourceType } from "./types";

export interface SourceTypeMeta {
  label: string;
  /** Lower number = listed first. */
  priority: number;
}

export const SOURCE_TYPES: Record<SourceType, SourceTypeMeta> = {
  FACT_CHECKER: { label: "Fact-checker", priority: 0 },
  GOVERNMENT: { label: "Government", priority: 1 },
  ACADEMIC: { label: "Academic", priority: 2 },
  REFERENCE: { label: "Reference", priority: 3 },
  NEWS: { label: "News", priority: 4 },
  OTHER: { label: "Website", priority: 5 },
  SOCIAL: { label: "Social media / forum", priority: 6 },
};

/** Old reports lack `sourceType`; anything unrecognised is treated as a generic website. */
export function toSourceType(value: unknown): SourceType {
  return typeof value === "string" && value in SOURCE_TYPES ? (value as SourceType) : "OTHER";
}

export const sourceTypeMeta = (type: unknown): SourceTypeMeta => SOURCE_TYPES[toSourceType(type)];

export const isSocial = (e: Evidence) => toSourceType(e.sourceType) === "SOCIAL";

/** Stable sort by publisher type priority (fact-checkers first, social media last). */
export function byTypePriority(items: Evidence[]): Evidence[] {
  return items
    .map((e, i) => ({ e, i }))
    .sort((a, b) => sourceTypeMeta(a.e.sourceType).priority - sourceTypeMeta(b.e.sourceType).priority || a.i - b.i)
    .map(({ e }) => e);
}

export function domainOf(evidence: Evidence): string {
  if (evidence.domain?.trim()) return evidence.domain.trim().replace(/^www\./i, "");
  try {
    return new URL(evidence.url).hostname.replace(/^www\./i, "");
  } catch {
    return "Unknown source";
  }
}

/** First letter/digit of the registrable-looking part of a domain, for the CSS letter avatar. */
export function avatarLetter(domain: string): string {
  const match = /[a-z0-9]/i.exec(domain);
  return match ? match[0].toUpperCase() : "?";
}

/** Deterministic palette slot (1–6) so the same domain always gets the same avatar colour. */
export function avatarSlot(domain: string): number {
  let hash = 0;
  for (let i = 0; i < domain.length; i++) hash = (hash * 31 + domain.charCodeAt(i)) >>> 0;
  return (hash % 6) + 1;
}
