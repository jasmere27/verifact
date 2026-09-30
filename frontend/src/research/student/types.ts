/** Types for ResearchFact Student Research Mode (`/api/v2/research/discover`, `/workspaces`). */

export type Category =
  | "RRL"
  | "RRS"
  | "LOCAL"
  | "FOREIGN"
  | "THEORIES"
  | "CONCEPTS"
  | "METHODS"
  | "RECENT"
  | "FOR_TEXT"
  | "SUPPORTING"
  | "CONTRADICTING";

export type Folder = "RRL" | "RRS" | "THEORY" | "CONCEPT" | "METHOD" | "EVIDENCE" | "OTHER";

export interface FoundSource {
  key: string;
  doi: string | null;
  url: string | null;
  title: string;
  authors: string[];
  year: number | null;
  venue: string | null;
  type: string | null;
  countries: string[];
  local: boolean;
  retracted: boolean;
  citedByCount: number | null;
  hasAbstract: boolean;
  relevance: string | null;
  relevanceQuote: string | null;
  stance: "SUPPORTS" | "CONTRADICTS" | null;
  verification: "VERIFIED" | "UNVERIFIED";
}

export interface Lead {
  name: string;
  verification: "VERIFIED" | "UNVERIFIED";
  sourceKeys: string[];
}

export interface Discovery {
  category: Category;
  topic: string;
  searches: string[];
  sources: FoundSource[];
  leads: Lead[];
  limitations: string[];
  notice: string;
  durationMs: number;
}

export interface SavedSource {
  key: string;
  folder: Folder;
  source: FoundSource;
  studentNote: string | null;
  savedAt: string;
}

export interface Workspace {
  id: string;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  topic: string;
  field: string | null;
  country: string | null;
  sources: SavedSource[];
  notes: string | null;
}
