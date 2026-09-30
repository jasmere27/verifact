import { useEffect, useRef } from "react";
import { formatDateTime, formatDuration, safeHttpUrl } from "../format";
import type { CheckedClaim, CheckedReference, ReferenceStatus, ResearchCheck, Support, WorkSummary } from "./types";

const REFERENCE_LABEL: Record<ReferenceStatus, { label: string; tone: string; meaning: string }> = {
  VERIFIED: { label: "Verified", tone: "good", meaning: "Found; details match." },
  FOUND_WITH_DIFFERENCES: { label: "Details differ", tone: "warn", meaning: "Found, but some details differ from the text." },
  RETRACTED: { label: "Retracted", tone: "bad", meaning: "A retraction notice is attached to this work." },
  NOT_FOUND: { label: "Not found", tone: "bad", meaning: "No matching record: possibly fabricated, or not indexed." },
  LOOKUP_FAILED: { label: "Lookup failed", tone: "muted", meaning: "A scholarly index was unavailable; try again." },
};

const SUPPORT_LABEL: Record<Support, { label: string; tone: string }> = {
  SUPPORTED: { label: "Supported by the abstract", tone: "good" },
  PARTIALLY_SUPPORTED: { label: "Partly supported", tone: "warn" },
  OVERSTATED: { label: "Overstated", tone: "warn" },
  CONTRADICTED: { label: "Contradicted by the abstract", tone: "bad" },
  NOT_ADDRESSED_IN_ABSTRACT: { label: "Not addressed in the abstract", tone: "muted" },
  NO_ABSTRACT: { label: "No abstract available", tone: "muted" },
  CITATION_PROBLEM: { label: "Citation problem", tone: "bad" },
  NEEDS_REVIEW: { label: "Needs review", tone: "warn" },
};

function authors(w: WorkSummary) {
  return w.authors.join(", ");
}

function WorkLine({ work }: { work: WorkSummary }) {
  const href = work.url ? safeHttpUrl(work.url) : null;
  return (
    <p className="research-work">
      {href ? (
        <a href={href} target="_blank" rel="noopener noreferrer nofollow">
          {work.title ?? work.doi}
        </a>
      ) : (
        <span>{work.title ?? work.doi}</span>
      )}
      <span className="muted small">
        {[authors(work), work.venue, work.year].filter(Boolean).join(" · ")}
        {work.citedByCount != null ? ` · cited ${work.citedByCount.toLocaleString()} times` : ""}
        {work.doi ? ` · doi:${work.doi}` : ""}
      </span>
    </p>
  );
}

function ReferenceCard({ r }: { r: CheckedReference }) {
  const s = REFERENCE_LABEL[r.status];
  return (
    <li className={`research-card tone-${s.tone}`}>
      <p className="research-card-head">
        <span className="muted small">{r.id}</span>
        <span className={`research-pill research-pill--${s.tone}`}>{s.label}</span>
      </p>
      <p className="research-as-written">{r.textAsWritten}</p>
      {r.work && <WorkLine work={r.work} />}
      {r.work && r.work.notices.length > 0 && (
        <p className="research-notice">Editorial notice: {r.work.notices.map((n) => n.replace(/_/g, " ")).join(", ")}</p>
      )}
      {r.differences.length > 0 && (
        <ul className="research-differences">
          {r.differences.map((d) => (
            <li key={d}>{d}</li>
          ))}
        </ul>
      )}
      {!r.work && r.status !== "LOOKUP_FAILED" && <p className="muted small">{s.meaning}</p>}
    </li>
  );
}

function ClaimCard({ c, refs }: { c: CheckedClaim; refs: Map<string, CheckedReference> }) {
  const s = SUPPORT_LABEL[c.support];
  const source = c.evidenceFrom ? refs.get(c.evidenceFrom)?.work : null;
  return (
    <li className={`research-card tone-${s.tone}`}>
      <p className="research-card-head">
        <span className="muted small">
          {c.id} · cites {c.referenceIds.join(", ")}
        </span>
        <span className={`research-pill research-pill--${s.tone}`}>{s.label}</span>
      </p>
      <p className="research-claim">“{c.quote}”</p>
      {c.evidenceQuote && (
        <div className="research-evidence">
          <p className="research-evidence-label">The cited abstract says{source?.title ? ` (${c.evidenceFrom})` : ""}:</p>
          <p className="research-quote">“{c.evidenceQuote}”</p>
        </div>
      )}
      {c.note && <p className="muted small">{c.note}</p>}
      {c.conflicting.length > 0 && (
        <div className="research-conflicts">
          <p className="research-evidence-label">Other research reports a different finding:</p>
          {c.conflicting.map((k, i) => (
            <div key={i} className="research-conflict">
              <p className="research-quote">“{k.quote}”</p>
              <WorkLine work={k.work} />
              {k.note && <p className="muted small">{k.note}</p>}
            </div>
          ))}
        </div>
      )}
    </li>
  );
}

export default function ResearchReport({ result, onNewCheck }: { result: ResearchCheck; onNewCheck: () => void }) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => headingRef.current?.focus(), []);
  const refs = new Map(result.references.map((r) => [r.id, r]));
  const rc = result.referenceCounts;
  const problems = (rc.NOT_FOUND ?? 0) + (rc.RETRACTED ?? 0) + (rc.FOUND_WITH_DIFFERENCES ?? 0);
  const sc = result.supportCounts;
  const claimIssues =
    (sc.CONTRADICTED ?? 0) + (sc.OVERSTATED ?? 0) + (sc.PARTIALLY_SUPPORTED ?? 0) + (sc.NEEDS_REVIEW ?? 0) + (sc.CITATION_PROBLEM ?? 0);

  return (
    <article className="report research-report" aria-labelledby="research-report-heading">
      <header className="research-hero">
        <p className="hero-kicker">ResearchFact · Citation check · {formatDateTime(result.createdAt)}</p>
        <h1 id="research-report-heading" ref={headingRef} tabIndex={-1}>
          {problems === 0 && claimIssues === 0
            ? "No citation problems found"
            : `${problems} reference ${problems === 1 ? "issue" : "issues"} · ${claimIssues} claim ${claimIssues === 1 ? "issue" : "issues"}`}
        </h1>
        <dl className="research-stats">
          <div>
            <dt>References</dt>
            <dd>
              {result.references.length} checked · {rc.VERIFIED ?? 0} verified · {rc.RETRACTED ?? 0} retracted ·{" "}
              {rc.NOT_FOUND ?? 0} not found
            </dd>
          </div>
          <div>
            <dt>Claims</dt>
            <dd>
              {result.claims.length} checked · {sc.SUPPORTED ?? 0} supported · {claimIssues} to review
            </dd>
          </div>
        </dl>
        <p className="research-disclaimer" role="note">
          {result.notice}
        </p>
        <div className="hero-actions research-actions">
          <button type="button" className="button button--secondary button--small" onClick={() => window.print()}>
            Print or save as PDF
          </button>
          <button type="button" className="button button--quiet button--small" onClick={onNewCheck}>
            Check another text
          </button>
        </div>
      </header>

      <section className="report-section" aria-labelledby="research-claims-heading">
        <h2 id="research-claims-heading">Claims and what the cited papers say</h2>
        {result.claims.length ? (
          <ul className="research-list">
            {result.claims.map((c) => (
              <ClaimCard key={c.id} c={c} refs={refs} />
            ))}
          </ul>
        ) : (
          <p className="muted">No cited claims were found; see the references below.</p>
        )}
      </section>

      <section className="report-section" aria-labelledby="research-refs-heading">
        <h2 id="research-refs-heading">References</h2>
        <ul className="research-list">
          {result.references.map((r) => (
            <ReferenceCard key={r.id} r={r} />
          ))}
        </ul>
      </section>

      {result.limitations.length > 0 && (
        <section className="report-section" aria-labelledby="research-limits-heading">
          <h2 id="research-limits-heading">Limitations</h2>
          <ul className="limitations">
            {result.limitations.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        </section>
      )}

      <p className="muted small">
        Records: Crossref, DataCite, OpenAlex (incl. Retraction Watch data) and PubMed. Quotes are short excerpts from
        abstracts; full papers are not read. Took {formatDuration(result.durationMs)}.
      </p>
    </article>
  );
}
