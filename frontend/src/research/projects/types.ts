/** Capstone projects (`/api/v2/me/projects`, ADR-21). */
import type { Category, Draft, Folder, FoundSource, Insights } from "../student/types";

export interface Question {
  id: string;
  text: string;
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
  | "CHECK_CITATIONS";

export interface NextStep {
  id: string;
  priority: Priority;
  title: string;
  detail: string;
  action: Action;
  category: Category | null;
  questionId: string | null;
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
