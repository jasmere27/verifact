/** Types for the NewsFact API (`/api/v2/news/checks`). */
import type { Evidence, Verdict } from "../types";

export type ClaimType = "FACT" | "STATISTIC" | "QUOTE" | "DATE_TIME" | "ATTRIBUTION";
export type ContextIssue = "NONE" | "OUTDATED" | "OLD_EVENT_AS_NEW" | "MISSING_CONTEXT" | "MISATTRIBUTED";
export type QuoteStatus = "NOT_A_QUOTE" | "FOUND_VERBATIM" | "NOT_LOCATED";
export type DecisionStatus = "UNREVIEWED" | "CONFIRMED" | "DISPUTED" | "NEEDS_WORK";

export interface SourceExcerpt {
  sourceId: string;
  excerpt: string;
}

export interface NewsClaim {
  id: string;
  type: ClaimType;
  articleQuote: string;
  claim: string;
  speaker: string | null;
  quotedWords: string | null;
  verdict: Verdict;
  supporting: SourceExcerpt | null;
  contradicting: SourceExcerpt | null;
  contextIssue: ContextIssue;
  quoteStatus: QuoteStatus;
  quoteSource: SourceExcerpt | null;
  sourcesConflict: boolean;
  explanation: string | null;
}

export interface NewsCheck {
  id: string;
  createdAt: string;
  articleUrl: string | null;
  articleTitle: string | null;
  articleDate: string | null;
  claims: NewsClaim[];
  sources: Evidence[];
  verdictCounts: Partial<Record<Verdict, number>>;
  limitations: string[];
  notice: string;
  searchProvider: string;
  durationMs: number;
}

export interface Decision {
  status: DecisionStatus;
  note: string | null;
}

export interface NewsReview {
  decisions: Record<string, Decision>;
  editorNote: string | null;
  updatedAt: string | null;
}

export interface NewsWorkspace {
  check: NewsCheck;
  review: NewsReview;
  /** Only in the response that created the workspace. */
  editToken: string | null;
}
