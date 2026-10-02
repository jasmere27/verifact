import type { Category, Folder } from "./types";

/** Search categories, folders and the folder a result is saved to by default (Student Research Mode). */
export const CATEGORIES: { value: Category; label: string; hint: string }[] = [
  { value: "RRL", label: "Related literature (RRL)", hint: "Reviews and key works on your topic" },
  { value: "RRS", label: "Related studies (RRS)", hint: "Empirical studies, last 10 years" },
  { value: "LOCAL", label: "Local studies", hint: "An author from your country" },
  { value: "FOREIGN", label: "Foreign studies", hint: "No author from your country" },
  { value: "THEORIES", label: "Theories & frameworks", hint: "Suggested, then verified in the literature" },
  { value: "CONCEPTS", label: "Key concepts", hint: "Concepts to define, with sources" },
  { value: "METHODS", label: "Research methods", hint: "Designs used for questions like yours" },
  { value: "RECENT", label: "Recent studies", hint: "Last 5 years" },
];

export const FOLDERS: { value: Folder; label: string }[] = [
  { value: "RRL", label: "RRL" },
  { value: "RRS", label: "RRS" },
  { value: "THEORY", label: "Theory / framework" },
  { value: "CONCEPT", label: "Concept" },
  { value: "METHOD", label: "Method" },
  { value: "EVIDENCE", label: "Supporting evidence" },
  { value: "OTHER", label: "Other" },
];

export const DEFAULT_FOLDER: Partial<Record<Category, Folder>> = {
  RRL: "RRL",
  RRS: "RRS",
  LOCAL: "RRS",
  FOREIGN: "RRS",
  THEORIES: "THEORY",
  CONCEPTS: "CONCEPT",
  METHODS: "METHOD",
  RECENT: "RRS",
  SUPPORTING: "EVIDENCE",
  FOR_TEXT: "EVIDENCE",
};
