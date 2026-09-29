import { excerpt } from "./format";
import type { OverallVerdict, VerificationResult } from "./types";

/** Recent checks live only in this browser's localStorage. All access is guarded. */
export interface RecentCheck {
  id: string;
  overallVerdict: OverallVerdict;
  label: string;
  createdAt: string;
}

const KEY = "verifact.recent.v1";
const MAX = 10;

function isRecentCheck(value: unknown): value is RecentCheck {
  if (!value || typeof value !== "object") return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.id === "string" &&
    typeof v.overallVerdict === "string" &&
    typeof v.label === "string" &&
    typeof v.createdAt === "string"
  );
}

export function loadRecent(): RecentCheck[] {
  try {
    const raw = window.localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter(isRecentCheck).slice(0, MAX) : [];
  } catch {
    return [];
  }
}

function save(items: RecentCheck[]) {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(items));
  } catch {
    // storage full or blocked: recent checks are a convenience only
  }
}

export function rememberCheck(result: VerificationResult): RecentCheck[] {
  const entry: RecentCheck = {
    id: result.id,
    overallVerdict: result.overallVerdict,
    label: excerpt(result.claims[0]?.text || result.input || "Untitled check", 120),
    createdAt: result.createdAt,
  };
  const next = [entry, ...loadRecent().filter((item) => item.id !== result.id)].slice(0, MAX);
  save(next);
  return next;
}

export function clearRecent() {
  try {
    window.localStorage.removeItem(KEY);
  } catch {
    // ignore
  }
}
