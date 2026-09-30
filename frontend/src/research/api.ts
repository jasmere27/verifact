import { streamResult } from "../api";
import type { StreamHandlers } from "../api";
import type { ResearchCheck } from "./types";

/** Two model calls plus scholarly lookups; just under the server's 180 s stream limit. */
const CHECK_TIMEOUT_MS = 170_000;

const asArray = <T>(value: unknown): T[] => (Array.isArray(value) ? (value as T[]) : []);

/** Defensive: guarantee the list fields exist so rendering never crashes on a partial payload. */
function normalize(raw: unknown): ResearchCheck {
  const r = (raw ?? {}) as ResearchCheck;
  return {
    ...r,
    references: asArray<ResearchCheck["references"][number]>(r.references).map((ref) => ({
      ...ref,
      differences: asArray(ref.differences),
      work: ref.work ? { ...ref.work, authors: asArray(ref.work.authors), notices: asArray(ref.work.notices) } : null,
    })),
    claims: asArray<ResearchCheck["claims"][number]>(r.claims).map((c) => ({
      ...c,
      referenceIds: asArray(c.referenceIds),
      conflicting: asArray<ResearchCheck["claims"][number]["conflicting"][number]>(c.conflicting).map((k) => ({
        ...k,
        work: { ...k.work, authors: asArray(k.work?.authors), notices: asArray(k.work?.notices) },
      })),
    })),
    referenceCounts: r.referenceCounts ?? {},
    supportCounts: r.supportCounts ?? {},
    limitations: asArray(r.limitations),
    notice: r.notice || "Automated check against scholarly records and abstracts. Confirm important points in the full text.",
  };
}

/** Streams progress (stage, claims found, references resolved) and resolves with the report. Nothing is stored. */
export function checkResearchStream(text: string, handlers: StreamHandlers, signal?: AbortSignal) {
  return streamResult(
    "/api/v2/research/check/stream",
    JSON.stringify({ text }),
    { "Content-Type": "application/json" },
    handlers,
    signal,
    normalize,
    CHECK_TIMEOUT_MS,
  );
}
