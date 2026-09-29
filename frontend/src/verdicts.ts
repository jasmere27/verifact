import type { EvidenceStrength, InputType, OverallVerdict } from "./types";

export interface VerdictMeta {
  label: string;
  /** One-line plain-language meaning, shown next to the label. */
  meaning: string;
  /** CSS class suffix → colour tokens in App.css. */
  tone: "supported" | "partly" | "misleading" | "contradicted" | "insufficient" | "mixed";
}

export const VERDICTS: Record<OverallVerdict, VerdictMeta> = {
  SUPPORTED: { label: "Supported", meaning: "The sources we found back this up.", tone: "supported" },
  PARTLY_SUPPORTED: {
    label: "Partly supported",
    meaning: "Some of it holds up; some details don't or couldn't be confirmed.",
    tone: "partly",
  },
  MISLEADING: {
    label: "Misleading",
    meaning: "Contains some truth but leaves a false impression.",
    tone: "misleading",
  },
  CONTRADICTED: { label: "Contradicted", meaning: "The sources we found say otherwise.", tone: "contradicted" },
  INSUFFICIENT_EVIDENCE: {
    label: "Not enough evidence",
    meaning: "We couldn't find enough reliable sources to judge this.",
    tone: "insufficient",
  },
  MIXED: { label: "Mixed results", meaning: "The claims checked received different verdicts.", tone: "mixed" },
};

const UNKNOWN: VerdictMeta = { label: "Unknown", meaning: "This verdict isn't recognised.", tone: "insufficient" };

export const verdictMeta = (v: string): VerdictMeta => VERDICTS[v as OverallVerdict] ?? UNKNOWN;

export const STRENGTH: Record<EvidenceStrength, { label: string; meaning: string }> = {
  STRONG: { label: "Strong", meaning: "Three or more independent sources agree." },
  MODERATE: { label: "Moderate", meaning: "Two sources, or the sources partly disagree." },
  LIMITED: { label: "Limited", meaning: "One source or none — treat with caution." },
};

export const strengthMeta = (s: string) =>
  STRENGTH[s as EvidenceStrength] ?? { label: "Unknown", meaning: "Evidence strength wasn't reported." };

export const INPUT_TYPE_LABEL: Record<InputType, string> = {
  TEXT: "Text",
  URL: "Link",
  IMAGE: "Image",
  AUDIO: "Audio",
};
