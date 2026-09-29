import { excerpt } from "./format";
import { reportPath } from "./router";
import { byTypePriority, domainOf, isSocial } from "./sources";
import type { Evidence, EvidenceStrength, VerificationResult } from "./types";
import { verdictMeta } from "./verdicts";

const MAX_REPLY = 400;

export const reportUrl = (id: string) => `${window.location.origin}${reportPath(id)}`;

/** Up to `max` distinct domains of cited, non-social sources, most authoritative first. */
export function citedDomains(result: VerificationResult, max = 2): string[] {
  const lookup = new Map(result.evidence.map((e) => [e.id, e]));
  const cited = result.claims
    .flatMap((c) => [...c.supportingEvidenceIds, ...c.contradictingEvidenceIds])
    .map((id) => lookup.get(id))
    .filter((e): e is Evidence => Boolean(e) && !isSocial(e as Evidence));
  const domains: string[] = [];
  for (const e of byTypePriority(cited)) {
    const d = domainOf(e);
    if (d !== "Unknown source" && !domains.includes(d)) domains.push(d);
    if (domains.length >= max) break;
  }
  return domains;
}

/**
 * Short, polite plain-text message for group chats:
 * VeriFact check: "<claim>" — <Verdict>. <summary> Sources: a.com, b.org <url>
 * The summary is trimmed so the whole message stays under ~400 characters.
 */
export function buildReply(result: VerificationResult): string {
  const label = verdictMeta(result.overallVerdict).label;
  const first = result.claims[0]?.text?.trim();
  const more = result.claims.length > 1 ? ` (+${result.claims.length - 1} more)` : "";
  const subject = first ? `"${excerpt(first, 110)}"${more}` : "this post";
  const domains = citedDomains(result);
  const head = `VeriFact check: ${subject} — ${label}.`;
  const tail = `${domains.length ? `Sources: ${domains.join(", ")} ` : ""}${reportUrl(result.id)}`;
  const budget = MAX_REPLY - head.length - tail.length - 2;
  const summary = result.summary?.trim() && budget > 40 ? excerpt(result.summary, budget) : "";
  return [head, summary, tail].filter(Boolean).join(" ");
}

const RANK: Record<EvidenceStrength, number> = { LIMITED: 1, MODERATE: 2, STRONG: 3 };

/** Strongest per-claim evidence strength, or null when it would be misleading (MIXED) or unknown. */
export function heroStrength(result: VerificationResult): EvidenceStrength | null {
  if (result.overallVerdict === "MIXED") return null;
  let best: EvidenceStrength | null = null;
  for (const c of result.claims) {
    const s = c.evidenceStrength;
    if (s in RANK && (!best || RANK[s] > RANK[best])) best = s;
  }
  return best;
}
