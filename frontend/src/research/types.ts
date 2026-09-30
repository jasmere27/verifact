/** Types for the ResearchFact API (`/api/v2/research/check`). */

export type ReferenceStatus = "VERIFIED" | "FOUND_WITH_DIFFERENCES" | "RETRACTED" | "NOT_FOUND" | "LOOKUP_FAILED";

export type Support =
  | "SUPPORTED"
  | "PARTIALLY_SUPPORTED"
  | "OVERSTATED"
  | "CONTRADICTED"
  | "NOT_ADDRESSED_IN_ABSTRACT"
  | "NO_ABSTRACT"
  | "CITATION_PROBLEM"
  | "NEEDS_REVIEW";

export interface WorkSummary {
  doi: string | null;
  url: string | null;
  title: string | null;
  authors: string[];
  year: number | null;
  venue: string | null;
  publisher: string | null;
  citedByCount: number | null;
  /** e.g. "retraction", "correction", "expression_of_concern" */
  notices: string[];
  hasAbstract: boolean;
}

export interface CheckedReference {
  id: string;
  textAsWritten: string;
  status: ReferenceStatus;
  differences: string[];
  work: WorkSummary | null;
}

export interface ConflictingWork {
  work: WorkSummary;
  quote: string;
  note: string | null;
}

export interface CheckedClaim {
  id: string;
  quote: string;
  claim: string;
  referenceIds: string[];
  support: Support;
  evidenceFrom: string | null;
  /** Verbatim from the cited abstract (≤300 chars). */
  evidenceQuote: string | null;
  note: string | null;
  conflicting: ConflictingWork[];
}

export interface ResearchCheck {
  createdAt: string;
  references: CheckedReference[];
  claims: CheckedClaim[];
  referenceCounts: Partial<Record<ReferenceStatus, number>>;
  supportCounts: Partial<Record<Support, number>>;
  limitations: string[];
  notice: string;
  durationMs: number;
}
