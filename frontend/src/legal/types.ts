/** Types for the LegalFact API (`/api/v2/legal/case-intelligence`). */

export type PracticeArea =
  | "EMPLOYMENT"
  | "HOUSING"
  | "FAMILY"
  | "CONSUMER"
  | "PERSONAL_INJURY"
  | "CRIMINAL"
  | "IMMIGRATION"
  | "DEBT_AND_BANKRUPTCY"
  | "WILLS_AND_ESTATES"
  | "BUSINESS_AND_CONTRACTS"
  | "REAL_ESTATE"
  | "INTELLECTUAL_PROPERTY"
  | "CIVIL_RIGHTS"
  | "OTHER";

/** Where a statement comes from. */
export type Basis = "USER_STATED" | "SOURCE_BACKED" | "AI_INTERPRETATION";

export type LegalSourceType = "STATUTE" | "REGULATION" | "OFFICIAL_GUIDANCE" | "GOVERNMENT" | "NEWS_OR_REPORT";

export interface Jurisdiction {
  status: "IDENTIFIED" | "UNCERTAIN" | "OUTSIDE_US";
  country: string | null;
  state: string | null;
  stateName: string | null;
  /** The user's own words the jurisdiction is based on. */
  basisQuote: string | null;
}

export interface Fact {
  statement: string;
  userQuote: string;
  /** As the user wrote it; null = not provided. */
  date: string | null;
  basis: Basis;
}

export interface TimelineEvent {
  date: string | null;
  approximate: boolean;
  event: string;
  userQuote: string;
  basis: Basis;
}

export interface SourceNote {
  sourceId: string;
  /** What the source says (SOURCE_BACKED). Null if it failed the server's checks; show the excerpt instead. */
  whatItSays: string | null;
  whatItSaysBasis: Basis;
  /** Why a professional might consult it (AI_INTERPRETATION). */
  relevance: string | null;
  relevanceBasis: Basis;
}

/** An inconsistency in the person's account: something to clarify, not a finding. */
export interface Conflict {
  description: string;
  userQuotes: string[];
  basis: Basis;
}

export interface Issue {
  id: string;
  topic: string;
  note: string | null;
  basis: Basis;
  sources: SourceNote[];
}

export interface MissingInformation {
  item: string;
  whyItMatters: string | null;
}

export interface LegalSource {
  id: string;
  url: string;
  domain: string;
  title: string;
  excerpt: string;
  publishedDate: string | null;
  retrievedAt: string;
  type: LegalSourceType;
}

export interface CaseIntelligence {
  createdAt: string;
  practiceAreas: PracticeArea[];
  jurisdiction: Jurisdiction;
  summary: string | null;
  keyFacts: Fact[];
  timeline: TimelineEvent[];
  conflicts: Conflict[];
  issues: Issue[];
  missingInformation: MissingInformation[];
  uncertainties: string[];
  sources: LegalSource[];
  notice: string;
  searchProvider: string;
  durationMs: number;
}
