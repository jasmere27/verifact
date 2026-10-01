import type { Evidence } from "./types";
import { domainOf } from "./sources";

/**
 * Citations for a report's web sources, for students' essays. Built only from what the report has (title,
 * site, date, URL); no author, because search results don't give one reliably. Students should still open
 * the source and check the details, as the report page says.
 */
export type CitationStyle = "APA" | "MLA";

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
/** MLA abbreviates months longer than four letters. */
const MLA_MONTHS = ["Jan.", "Feb.", "Mar.", "Apr.", "May", "June", "July", "Aug.", "Sept.", "Oct.", "Nov.", "Dec."];

interface Ymd {
  year: number;
  month: number;
  day: number;
}

/** Search providers send dates in several formats; anything unparseable counts as no date. */
export function parseDate(value: string | null | undefined): Ymd | null {
  if (!value?.trim()) return null;
  const trimmed = value.trim();
  const time = Date.parse(trimmed);
  if (Number.isNaN(time)) return null;
  const d = new Date(time);
  // ISO dates/instants are UTC; free text like "May 1, 2024" is parsed as local midnight, so read it locally
  // (in UTC it could fall on the previous day).
  return /^\d{4}-\d{2}-\d{2}/.test(trimmed)
    ? { year: d.getUTCFullYear(), month: d.getUTCMonth(), day: d.getUTCDate() }
    : { year: d.getFullYear(), month: d.getMonth(), day: d.getDate() };
}

function siteName(evidence: Evidence): string {
  return domainOf(evidence).replace(/^www\./, "");
}

function title(evidence: Evidence): string {
  const t = (evidence.title ?? "").trim().replace(/\s+/g, " ");
  return t || siteName(evidence);
}

/** Ends with exactly one full stop (titles may already end in ".", "?" or "!"). */
function sentence(text: string): string {
  return /[.?!]$/.test(text) ? text : `${text}.`;
}

/** APA 7, web page without an author: Title. (Year, Month Day). Site. URL */
export function apaWeb(evidence: Evidence): string {
  const published = parseDate(evidence.publishedDate);
  const retrieved = parseDate(evidence.retrievedAt);
  const site = siteName(evidence);
  if (published) {
    return `${sentence(title(evidence))} (${published.year}, ${MONTHS[published.month]} ${published.day}). ${site}. ${evidence.url}`;
  }
  // No date: APA uses "n.d." and a retrieval date, since undated pages may change.
  const when = retrieved ? ` Retrieved ${MONTHS[retrieved.month]} ${retrieved.day}, ${retrieved.year}, from` : "";
  return `${sentence(title(evidence))} (n.d.). ${site}.${when} ${evidence.url}`;
}

/** MLA 9: "Title." Site, Day Mon. Year, url. Accessed Day Mon. Year. */
export function mlaWeb(evidence: Evidence): string {
  const published = parseDate(evidence.publishedDate);
  const retrieved = parseDate(evidence.retrievedAt);
  const quoted = `“${sentence(title(evidence))}”`;
  const date = published ? ` ${published.day} ${MLA_MONTHS[published.month]} ${published.year},` : "";
  const url = evidence.url.replace(/^https?:\/\//, "");
  const accessed = retrieved ? ` Accessed ${retrieved.day} ${MLA_MONTHS[retrieved.month]} ${retrieved.year}.` : "";
  return `${quoted} ${siteName(evidence)},${date} ${url}.${accessed}`;
}

export function cite(evidence: Evidence, style: CitationStyle): string {
  return style === "APA" ? apaWeb(evidence) : mlaWeb(evidence);
}

/** Reference lists are alphabetical (by title here, as there are no authors). */
export function citeAll(items: Evidence[], style: CitationStyle): string[] {
  return items
    .map((e) => cite(e, style))
    .sort((a, b) => a.replace(/^“/, "").localeCompare(b.replace(/^“/, ""), undefined, { sensitivity: "base" }));
}
