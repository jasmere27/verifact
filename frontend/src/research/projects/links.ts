import type { LinkNote, LinkRole, LinkStance } from "./types";

export const ROLE_LABEL: Record<LinkRole, string> = {
  FINDING: "Finding",
  METHOD: "Method you could use",
  BACKGROUND: "Background",
};

export const STANCE_LABEL: Record<LinkStance, string> = {
  SUPPORTS: "Supports your expected answer",
  CONTRADICTS: "Points the other way",
  MIXED: "Mixed",
};

export type Group = "SUPPORTS" | "CONTRADICTS" | "MIXED" | "FINDING" | "METHOD" | "BACKGROUND" | "MANUAL";

export const GROUP_ORDER: Group[] = ["SUPPORTS", "CONTRADICTS", "MIXED", "FINDING", "METHOD", "BACKGROUND", "MANUAL"];

export const GROUP_LABEL: Record<Group, string> = {
  ...STANCE_LABEL,
  FINDING: "Findings",
  METHOD: "Methods you could use",
  BACKGROUND: "Background",
  MANUAL: "Linked by you",
};

/** Where a linked source goes in a question's evidence: its stance, else its role, else "linked by you". */
export function groupOf(note: LinkNote | undefined): Group {
  if (!note) return "MANUAL";
  if (note.role === "FINDING" && note.stance) return note.stance;
  return note.role;
}
