import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import {
  formatDate,
  formatDateTime,
  formatDuration,
  formatPublishedDate,
  plural,
  safeHttpUrl,
} from "../format";
import { reportPath } from "../router";
import type { ClaimAssessment, Evidence, VerificationResult } from "../types";
import { INPUT_TYPE_LABEL, strengthMeta, verdictMeta } from "../verdicts";
import Link from "./Link";
import VerdictBadge from "./VerdictBadge";
import VerdictIcon from "./VerdictIcon";

function domainOf(evidence: Evidence): string {
  if (evidence.domain?.trim()) return evidence.domain.trim();
  try {
    return new URL(evidence.url).hostname.replace(/^www\./, "");
  } catch {
    return "Unknown source";
  }
}

function SourceItem({ evidence }: { evidence: Evidence }) {
  const href = safeHttpUrl(evidence.url ?? "");
  const domain = domainOf(evidence);
  const title = evidence.title?.trim() || domain;
  return (
    <li className="source">
      <p className="source-meta">
        <span className="source-domain">{domain}</span>
        <span aria-hidden="true"> · </span>
        <span>{evidence.publishedDate ? `Published ${formatPublishedDate(evidence.publishedDate)}` : "Date unknown"}</span>
      </p>
      <p className="source-title">
        {href ? (
          <a href={href} target="_blank" rel="noopener noreferrer nofollow">
            {title}
            <span className="visually-hidden"> (opens in a new tab)</span>
          </a>
        ) : (
          title
        )}
      </p>
      {evidence.snippet?.trim() && <blockquote className="source-quote">{evidence.snippet.trim()}</blockquote>}
      <p className="source-foot">
        Retrieved {formatDate(evidence.retrievedAt)} <span className="source-id">· {evidence.id}</span>
      </p>
    </li>
  );
}

function SourceGroup({
  heading,
  items,
  empty,
  kind,
}: {
  heading: string;
  items: Evidence[];
  empty: string;
  kind: "support" | "contra";
}) {
  return (
    <div className={`source-group source-group--${kind}`}>
      <h4>
        {heading} <span className="count">({items.length})</span>
      </h4>
      {items.length ? (
        <ul className="source-list">
          {items.map((e) => (
            <SourceItem key={e.id} evidence={e} />
          ))}
        </ul>
      ) : (
        <p className="muted small">{empty}</p>
      )}
    </div>
  );
}

function ClaimCard({ claim, index, lookup }: { claim: ClaimAssessment; index: number; lookup: Map<string, Evidence> }) {
  const meta = verdictMeta(claim.verdict);
  const strength = strengthMeta(claim.evidenceStrength);
  const resolve = (ids: string[]) => ids.map((id) => lookup.get(id)).filter((e): e is Evidence => Boolean(e));
  const supporting = resolve(claim.supportingEvidenceIds);
  const contradicting = resolve(claim.contradictingEvidenceIds);
  return (
    <article className={`claim-card tone-${meta.tone}`} aria-labelledby={`claim-${index}-text`}>
      <header className="claim-head">
        <span className="claim-number">Claim {index + 1}</span>
        <VerdictBadge verdict={claim.verdict} />
      </header>
      <h3 id={`claim-${index}-text`} className="claim-text">
        {claim.text}
      </h3>
      <p className="claim-meaning">{meta.meaning}</p>
      <dl className="strength">
        <dt>Evidence strength</dt>
        <dd>
          <span className={`strength-meter strength-meter--${claim.evidenceStrength?.toLowerCase()}`} aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <strong>{strength.label}</strong>
          <span className="strength-meaning"> — {strength.meaning}</span>
        </dd>
      </dl>
      {claim.explanation?.trim() && (
        <div className="claim-why">
          <h4>Why</h4>
          <p>{claim.explanation}</p>
        </div>
      )}
      <div className="claim-sources">
        <SourceGroup
          kind="support"
          heading="Supporting sources"
          items={supporting}
          empty="No sources were cited in support."
        />
        <SourceGroup
          kind="contra"
          heading="Contradicting sources"
          items={contradicting}
          empty="No sources were cited against this."
        />
      </div>
    </article>
  );
}

function CopyLinkButton({ id }: { id: string }) {
  const [state, setState] = useState<"idle" | "copied" | "manual">("idle");
  const url = `${window.location.origin}${reportPath(id)}`;

  useEffect(() => {
    if (state !== "copied") return;
    const timer = window.setTimeout(() => setState("idle"), 2500);
    return () => window.clearTimeout(timer);
  }, [state]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setState("copied");
    } catch {
      setState("manual");
    }
  }

  return (
    <span className="copy-link">
      <button type="button" className="button button--secondary" onClick={copy}>
        {state === "copied" ? "Link copied" : "Copy link"}
      </button>
      <span className="visually-hidden" aria-live="polite">
        {state === "copied" ? "Link copied to clipboard" : ""}
      </span>
      {state === "manual" && (
        <label className="manual-copy">
          <span className="small muted">Copy this link:</span>
          <input readOnly value={url} onFocus={(e) => e.currentTarget.select()} autoFocus />
        </label>
      )}
    </span>
  );
}

function Collapsible({ summary, children }: { summary: string; children: ReactNode }) {
  return (
    <details className="collapsible">
      <summary>{summary}</summary>
      <div className="collapsible-body">{children}</div>
    </details>
  );
}

export default function Report({ result }: { result: VerificationResult }) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus();
  }, [result.id]);

  const overall = verdictMeta(result.overallVerdict);
  const lookup = new Map(result.evidence.map((e) => [e.id, e]));
  const cited = new Set(result.claims.flatMap((c) => [...c.supportingEvidenceIds, ...c.contradictingEvidenceIds]));
  const uncited = result.evidence.filter((e) => !cited.has(e.id));
  const inputType = INPUT_TYPE_LABEL[result.inputType] ?? result.inputType;
  const inputHref = result.inputType === "URL" ? safeHttpUrl(result.input) : null;

  return (
    <article className="report" aria-labelledby="report-heading">
      <div className="report-toolbar">
        <Link href="/" className="back-link">
          <span aria-hidden="true">←</span> Check something else
        </Link>
        <CopyLinkButton id={result.id} />
      </div>

      <header className="report-header">
        <p className="eyebrow">
          Verification report · {inputType} · {formatDateTime(result.createdAt)}
        </p>
        <h1 id="report-heading" ref={headingRef} tabIndex={-1}>
          What we checked and what we found
        </h1>
      </header>

      <section className="report-section" aria-labelledby="checked-heading">
        <h2 id="checked-heading">What was checked</h2>
        {result.claims.length ? (
          <ol className="checked-claims">
            {result.claims.map((c) => (
              <li key={c.id}>{c.text}</li>
            ))}
          </ol>
        ) : (
          <p className="muted">No specific factual claims were identified in this input.</p>
        )}
        <div className="collapsibles">
          <Collapsible summary={`Original input (${inputType.toLowerCase()})`}>
            {inputHref ? (
              <a href={inputHref} target="_blank" rel="noopener noreferrer nofollow" className="break">
                {result.input}
              </a>
            ) : (
              <p className="prewrap">{result.input}</p>
            )}
          </Collapsible>
          {result.checkedText?.trim() && (
            <Collapsible summary="Text that was analysed (excerpt)">
              <p className="prewrap">{result.checkedText}</p>
            </Collapsible>
          )}
        </div>
      </section>

      <section className={`overall tone-${overall.tone}`} aria-labelledby="overall-heading">
        <h2 id="overall-heading" className="overall-kicker">
          Overall assessment
        </h2>
        <p className="overall-verdict">
          <VerdictIcon tone={overall.tone} size={28} />
          <span>{overall.label}</span>
        </p>
        <p className="overall-meaning">{overall.meaning}</p>
        {result.summary?.trim() && <p className="overall-summary">{result.summary}</p>}
      </section>

      {result.claims.length > 0 && (
        <section className="report-section" aria-labelledby="claims-heading">
          <h2 id="claims-heading">Claim by claim</h2>
          <div className="claim-list">
            {result.claims.map((claim, index) => (
              <ClaimCard key={claim.id} claim={claim} index={index} lookup={lookup} />
            ))}
          </div>
        </section>
      )}

      {uncited.length > 0 && (
        <section className="report-section" aria-labelledby="other-heading">
          <h2 id="other-heading">Other sources reviewed</h2>
          <p className="muted small">
            Retrieved during the search but not cited for any claim — usually because they were off-topic or added
            nothing new.
          </p>
          <details className="collapsible">
            <summary>Show {plural(uncited.length, "source")}</summary>
            <ul className="source-list collapsible-body">
              {uncited.map((e) => (
                <SourceItem key={e.id} evidence={e} />
              ))}
            </ul>
          </details>
        </section>
      )}

      <section className="report-section" aria-labelledby="limits-heading">
        <h2 id="limits-heading">What remains uncertain</h2>
        {result.limitations.length ? (
          <ul className="limitations">
            {result.limitations.map((item, i) => (
              <li key={i}>{item}</li>
            ))}
          </ul>
        ) : (
          <p className="muted">No specific limitations were reported for this check.</p>
        )}
        <p className="standing-note">
          VeriFact is an aid, not a final authority. It only knows what its web search found at the time of the check.
          Read the sources, and weigh them yourself before relying on a verdict.
        </p>
      </section>

      <footer className="report-meta">
        <dl>
          <div>
            <dt>Checked at</dt>
            <dd>{formatDateTime(result.createdAt)}</dd>
          </div>
          <div>
            <dt>Search provider</dt>
            <dd>{result.searchProvider || "—"}</dd>
          </div>
          <div>
            <dt>Time taken</dt>
            <dd>{formatDuration(result.durationMs)}</dd>
          </div>
          <div>
            <dt>Sources retrieved</dt>
            <dd>{result.evidence.length}</dd>
          </div>
        </dl>
        <p className="report-id">Report ID {result.id}</p>
      </footer>

      <div className="report-actions">
        <CopyLinkButton id={result.id} />
        <Link href="/" className="button button--primary">
          Check something else
        </Link>
      </div>
      <p className="small muted">
        Anyone with this report&apos;s link can view it, including what was submitted.
      </p>
    </article>
  );
}
