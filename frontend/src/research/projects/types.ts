/** Capstone projects (`/api/v2/me/projects`, ADR-21). */
import type { Category, Draft, Folder, FoundSource, Insights } from "../student/types";

export interface Question {
  id: string;
  text: string;
  /** What the student expects to find, in their own words. */
  hypothesis: string | null;
}

/* ---------- Source ↔ question links (ADR-24) ---------- */

/** FINDING: a result about the question · METHOD: a design or instrument to reuse · BACKGROUND: context or theory. */
export type LinkRole = "FINDING" | "METHOD" | "BACKGROUND";
/** A finding compared with the expected answer. */
export type LinkStance = "SUPPORTS" | "CONTRADICTS" | "MIXED";

/** The AI's reading kept with an accepted link: `how` is AI interpretation, `quote` the abstract's own words. */
export interface LinkNote {
  questionId: string;
  role: LinkRole;
  stance: LinkStance | null;
  how: string;
  quote: string;
}

export interface LinkSuggestion extends LinkNote {
  key: string;
  title: string;
}

export type ReadingStatus = "TO_READ" | "READ" | "CITED";

export interface LibraryItem {
  key: string;
  folder: Folder;
  /** Verified index record. */
  source: FoundSource;
  status: ReadingStatus | null;
  /** The student's own fields. */
  note: string | null;
  keyFindings: string | null;
  method: string | null;
  questionIds: string[];
  savedAt: string;
  /** Readings for links accepted from suggestions. */
  linkNotes: LinkNote[];
}

export interface GapNote {
  id: string;
  statement: string;
  sourceKeys: string[];
}

export interface Milestone {
  id: string;
  label: string;
  done: boolean;
  detail: string | null;
}

export type Priority = "HIGH" | "MEDIUM" | "LOW";

export type Action =
  | "ADD_QUESTIONS"
  | "FIND_SOURCES"
  | "LINK_SOURCES"
  | "OPEN_LIBRARY"
  | "REVIEW_RETRACTED"
  | "WRITE_GAP"
  | "GENERATE_INSIGHTS"
  | "UPLOAD_DRAFT"
  | "REVIEW_DRAFT_CLAIMS"
  | "CHECK_CITATIONS"
  | "OPEN_FILES"
  | "REVIEW_LINKS";

export interface NextStep {
  id: string;
  priority: Priority;
  title: string;
  detail: string;
  action: Action;
  category: Category | null;
  questionId: string | null;
  /** Draft and paper steps: the file to open. */
  fileId: string | null;
  basis: string[];
}

export interface Project {
  id: string;
  createdAt: string;
  updatedAt: string;
  deletesAt: string;
  title: string;
  field: string | null;
  country: string | null;
  questions: Question[];
  library: LibraryItem[];
  gaps: GapNote[];
  notes: string | null;
  draft: Draft | null;
  insights: Insights | null;
  files: FileSummary[];
  /** AI-suggested links waiting for the student. */
  suggestions: LinkSuggestion[];
  progress: { percent: number; milestones: Milestone[] };
  nextSteps: NextStep[];
}

export interface ProjectSummary {
  id: string;
  title: string;
  updatedAt: string;
  deletesAt: string;
  sources: number;
  questions: number;
  percent: number;
}

/** RQ1, RQ2… by position. */
export function questionLabel(questions: Question[], id: string): string {
  const i = questions.findIndex((q) => q.id === id);
  return i < 0 ? "RQ" : `RQ${i + 1}`;
}

/* ---------- Files (ADR-23): chapter drafts and research papers ---------- */

export type FileKind = "DRAFT" | "PAPER";
export type MatchStatus = "VERIFIED" | "POSSIBLE" | "NOT_FOUND" | "LOOKUP_FAILED";

export interface FileSummary {
  id: string;
  kind: FileKind;
  label: string | null;
  fileName: string | null;
  uploadedAt: string;
  pages: number;
  needsCitation: number | null;
  referenceEntries: number;
  match: MatchStatus | null;
  /** The matched record's key (DOI), when it can be added to the library. */
  matchedKey: string | null;
  title: string | null;
  findings: number;
}

export interface Quoted {
  statement: string;
  /** The paper's own words. */
  quote: string;
}

export type Aspect = "DESIGN" | "PARTICIPANTS" | "SETTING" | "INSTRUMENTS" | "ANALYSIS";

export interface PaperAnalysis {
  analyzedAt: string;
  match: { status: MatchStatus; source: FoundSource | null; basis: string };
  plainSummary: string | null;
  findings: Quoted[];
  method: { aspect: Aspect; statement: string; quote: string }[];
  authorLimitations: Quoted[];
  limitations: string[];
  notice: string;
}

export interface ProjectFile {
  id: string;
  kind: FileKind;
  label: string | null;
  fileName: string | null;
  docKind: "PDF" | "DOCX" | "PPTX" | "TXT";
  pages: number;
  chars: number;
  truncated: boolean;
  uploadedAt: string;
  draft: Draft | null;
  paper: PaperAnalysis | null;
}

export function fileName(f: { kind: FileKind; label: string | null; title?: string | null; fileName: string | null }): string {
  return f.label || f.title || f.fileName || (f.kind === "DRAFT" ? "Draft" : "Paper");
}
