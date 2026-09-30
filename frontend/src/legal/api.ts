import { streamResult } from "../api";
import type { StreamHandlers } from "../api";
import type { CaseIntelligence } from "./types";

const asArray = <T>(value: unknown): T[] => (Array.isArray(value) ? (value as T[]) : []);

/** Defensive: guarantee the list fields exist so rendering never crashes on a partial payload. */
function normalize(raw: unknown): CaseIntelligence {
  const r = (raw ?? {}) as CaseIntelligence;
  return {
    ...r,
    practiceAreas: asArray(r.practiceAreas),
    jurisdiction: r.jurisdiction ?? { status: "UNCERTAIN", country: null, state: null, stateName: null, basisQuote: null },
    keyFacts: asArray(r.keyFacts),
    timeline: asArray(r.timeline),
    issues: asArray<CaseIntelligence["issues"][number]>(r.issues).map((i) => ({ ...i, sources: asArray(i.sources) })),
    missingInformation: asArray(r.missingInformation),
    uncertainties: asArray(r.uncertainties),
    sources: asArray(r.sources),
    notice: r.notice || "AI assistance, not legal advice. A licensed attorney should review it.",
  };
}

/** Streams progress (stage, issue topics, sources found) and resolves with the report. Nothing is stored. */
export function analyzeCaseStream(description: string, handlers: StreamHandlers, signal?: AbortSignal) {
  return streamResult(
    "/api/v2/legal/case-intelligence/stream",
    JSON.stringify({ description }),
    { "Content-Type": "application/json" },
    handlers,
    signal,
    normalize,
  );
}
