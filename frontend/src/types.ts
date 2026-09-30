/** Types for the v2 verification API (`/api/v2/verifications`). */

export type Verdict = "SUPPORTED" | "PARTLY_SUPPORTED" | "MISLEADING" | "CONTRADICTED" | "INSUFFICIENT_EVIDENCE";
export type OverallVerdict = Verdict | "MIXED";
export type EvidenceStrength = "STRONG" | "MODERATE" | "LIMITED";
export type InputType = "TEXT" | "URL" | "IMAGE" | "AUDIO";
export type SourceType = "FACT_CHECKER" | "NEWS" | "REFERENCE" | "GOVERNMENT" | "ACADEMIC" | "SOCIAL" | "OTHER";

export interface Evidence {
  id: string;
  url: string;
  domain: string;
  title: string;
  snippet: string;
  /** Format varies by search provider; may be free text. */
  publishedDate: string | null;
  /** ISO instant. */
  retrievedAt: string;
  /** Kind of publisher. Missing on reports stored before it existed; normalised to "OTHER" by the API client. */
  sourceType: SourceType;
}

export interface ClaimAssessment {
  /** e.g. "C1" */
  id: string;
  text: string;
  verdict: Verdict;
  evidenceStrength: EvidenceStrength;
  explanation: string;
  supportingEvidenceIds: string[];
  contradictingEvidenceIds: string[];
}

export type ImageKind =
  | "SOCIAL_MEDIA_POST"
  | "NEWS_HEADLINE"
  | "ARTICLE"
  | "CHART"
  | "MEME"
  | "PHOTO"
  | "DOCUMENT"
  | "OTHER";

/** What an uploaded image visibly shows, as read by the vision model. Context only, not evidence. */
export interface ImageContext {
  kind: ImageKind;
  /** Account, person or outlet the image presents as its source, as written in it. */
  shownSource: string | null;
  /** A date written in the image, as written. */
  shownDate: string | null;
  description: string | null;
}

export interface VerificationResult {
  id: string;
  /** ISO instant. */
  createdAt: string;
  inputType: InputType;
  /** What the user submitted: the text, the link, or the file name. */
  input: string;
  /** Excerpt of the text that was analysed. */
  checkedText: string;
  overallVerdict: OverallVerdict;
  summary: string;
  claims: ClaimAssessment[];
  /** Every retrieved source; cited ones are referenced from claims. */
  evidence: Evidence[];
  limitations: string[];
  searchProvider: string;
  durationMs: number;
  /** Only for images read by the vision model; null for everything else and for older reports. */
  imageContext: ImageContext | null;
}

/** Pipeline stages reported by the streaming endpoints, in order (some may be skipped). */
export type StageId = "READING_INPUT" | "EXTRACTING_CLAIMS" | "SEARCHING" | "ASSESSING";

export interface SourcesFound {
  count: number;
  domains: string[];
}
