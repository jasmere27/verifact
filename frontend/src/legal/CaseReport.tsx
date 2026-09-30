import { useEffect, useRef } from "react";
import type { ReactNode } from "react";
import { formatDateTime, formatDuration, safeHttpUrl } from "../format";
import type { Basis, CaseIntelligence, LegalSource, LegalSourceType, PracticeArea } from "./types";

const PRACTICE_AREA_LABEL: Record<PracticeArea, string> = {
  EMPLOYMENT: "Employment",
  HOUSING: "Housing / landlord–tenant",
  FAMILY: "Family",
  CONSUMER: "Consumer",
  PERSONAL_INJURY: "Personal injury",
  CRIMINAL: "Criminal",
  IMMIGRATION: "Immigration",
  DEBT_AND_BANKRUPTCY: "Debt and bankruptcy",
  WILLS_AND_ESTATES: "Wills and estates",
  BUSINESS_AND_CONTRACTS: "Business and contracts",
  REAL_ESTATE: "Real estate",
  INTELLECTUAL_PROPERTY: "Intellectual property",
  CIVIL_RIGHTS: "Civil rights",
  OTHER: "Other / unclear",
};

const BASIS_LABEL: Record<Basis, string> = {
  USER_STATED: "User-stated",
  SOURCE_BACKED: "From source",
  AI_INTERPRETATION: "AI summary",
};

const BASIS_TITLE: Record<Basis, string> = {
  USER_STATED: "The person's own account. Not verified.",
  SOURCE_BACKED: "What the linked official source says, about the law, not about this situation.",
  AI_INTERPRETATION: "Written by AI to organise the information. Check it against the description.",
};

const SOURCE_TYPE_LABEL: Record<LegalSourceType, string> = {
  STATUTE: "Statute",
  REGULATION: "Regulation",
  OFFICIAL_GUIDANCE: "Agency guidance",
  GOVERNMENT: "Government",
  NEWS_OR_REPORT: "News release / report",
};

/** States with curated official sources (mirrors backend `LegalSources.STATE`). */
const STATES_WITH_SOURCES: Record<string, string> = { CA: "California" };

function BasisChip({ basis }: { basis: Basis }) {
  return (
    <span className={`basis-chip basis-chip--${basis.toLowerCase()}`} title={BASIS_TITLE[basis]}>
      {BASIS_LABEL[basis]}
    </span>
  );
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section className="report-section" aria-labelledby={id}>
      <h2 id={id}>{title}</h2>
      {children}
    </section>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="muted">{children}</p>;
}

function SourceLink({ source }: { source: LegalSource }) {
  const href = safeHttpUrl(source.url);
  return href ? (
    <a href={href} target="_blank" rel="noopener noreferrer nofollow">
      {source.title || source.domain}
    </a>
  ) : (
    <span>{source.title || source.domain}</span>
  );
}

function SourceList({ sources }: { sources: LegalSource[] }) {
  return (
    <ol className="legal-sources">
      {sources.map((s) => (
        <li key={s.id}>
          <p className="legal-source-head">
            <span className="muted small">{s.id}</span>
            <span className={`type-badge legal-type legal-type--${s.type.toLowerCase()}`}>{SOURCE_TYPE_LABEL[s.type]}</span>
            <SourceLink source={s} />
          </p>
          <p className="legal-quote">“{s.excerpt}”</p>
          <p className="muted small">
            {s.domain}
            {s.publishedDate ? ` · published ${s.publishedDate}` : ""} · retrieved {formatDateTime(s.retrievedAt)}
          </p>
        </li>
      ))}
    </ol>
  );
}

export default function CaseReport({ result, onNewCase }: { result: CaseIntelligence; onNewCase: () => void }) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => headingRef.current?.focus(), []);
  const sourcesById = new Map(result.sources.map((s) => [s.id, s]));
  const j = result.jurisdiction;
  const outsideUs = j.status === "OUTSIDE_US";
  const matchedIds = new Set(result.issues.flatMap((i) => i.sources.map((n) => n.sourceId)));
  const matched = result.sources.filter((s) => matchedIds.has(s.id));
  const unmatched = result.sources.filter((s) => !matchedIds.has(s.id));
  const stateSources = j.state ? STATES_WITH_SOURCES[j.state] : undefined;
  const searched = outsideUs
    ? "No sources were searched: the situation is outside LegalFact's US coverage."
    : `Searched: federal statutes, regulations and agency sites${stateSources ? `, plus ${stateSources} official sites` : ""}. Case law is not included yet.`;

  return (
    <article className="report legal-report" aria-labelledby="case-heading">
      <header className="legal-hero">
        <p className="hero-kicker">
          LegalFact · Case intelligence · {formatDateTime(result.createdAt)}
        </p>
        <h1 id="case-heading" ref={headingRef} tabIndex={-1}>
          {result.practiceAreas.map((a) => PRACTICE_AREA_LABEL[a] ?? a).join(" · ")}
        </h1>
        <dl className="legal-facts-grid">
          <div>
            <dt>Jurisdiction</dt>
            <dd>
              {j.status === "IDENTIFIED" && <>{j.stateName}, United States</>}
              {j.status === "UNCERTAIN" && <>Uncertain{j.country ? " (United States)" : ""}: state not clear</>}
              {j.status === "OUTSIDE_US" && <>{j.country}: outside LegalFact&apos;s US coverage</>}
              {j.basisQuote && <span className="legal-basis">Based on: “{j.basisQuote}”</span>}
            </dd>
          </div>
          <div>
            <dt>Sources retrieved</dt>
            <dd>
              {result.sources.length}
              <span className="legal-basis">Official sites; not reviewed for applicability</span>
            </dd>
          </div>
        </dl>
        <p className="legal-notice" role="note">
          <strong>{result.notice}</strong>
        </p>
        <div className="hero-actions legal-actions">
          <button type="button" className="button button--secondary button--small" onClick={() => window.print()}>
            Print or save as PDF
          </button>
          <button type="button" className="button button--quiet button--small" onClick={onNewCase}>
            Analyse another description
          </button>
        </div>
      </header>

      <Section id="summary-heading" title="Summary">
        {result.summary ? (
          <p className="legal-statement">
            <BasisChip basis="AI_INTERPRETATION" /> {result.summary}
          </p>
        ) : (
          <Empty>No summary could be written without legal conclusions. See the facts below.</Empty>
        )}
      </Section>

      <Section id="facts-heading" title="Key facts (as described)">
        {result.keyFacts.length ? (
          <ul className="legal-list">
            {result.keyFacts.map((f, i) => (
              <li key={i}>
                <p className="legal-statement">
                  <BasisChip basis={f.basis} /> {f.statement}
                  {f.date && <span className="legal-date"> · {f.date}</span>}
                </p>
                <p className="legal-quote">“{f.userQuote}”</p>
              </li>
            ))}
          </ul>
        ) : (
          <Empty>No specific facts could be quoted from the description.</Empty>
        )}
      </Section>

      <Section id="timeline-heading" title="Timeline (as described)">
        {result.timeline.length ? (
          <ol className="legal-timeline">
            {result.timeline.map((e, i) => (
              <li key={i}>
                <span className="legal-timeline-date">
                  {e.date ?? "Date not provided"}
                  {e.date && e.approximate && <span className="legal-approx">approx.</span>}
                </span>
                <span className="legal-timeline-event">
                  <span>
                    <BasisChip basis={e.basis} /> {e.event}
                  </span>
                  <span className="legal-quote">“{e.userQuote}”</span>
                </span>
              </li>
            ))}
          </ol>
        ) : (
          <Empty>No dated events were described.</Empty>
        )}
      </Section>

      {result.conflicts.length > 0 && (
        <Section id="conflicts-heading" title="Inconsistencies to clarify">
          <ul className="legal-list">
            {result.conflicts.map((c, i) => (
              <li key={i}>
                <p className="legal-statement">
                  <BasisChip basis={c.basis} /> {c.description}
                </p>
                {c.userQuotes.map((q) => (
                  <p key={q} className="legal-quote">
                    “{q}”
                  </p>
                ))}
              </li>
            ))}
          </ul>
        </Section>
      )}

      <Section id="issues-heading" title="Topics a professional may want to review">
        {outsideUs ? (
          <Empty>Legal topics aren&apos;t shown for situations outside the United States.</Empty>
        ) : result.issues.length ? (
          <div className="claim-list">
            {result.issues.map((issue) => (
              <div key={issue.id} className="legal-issue">
                <h3>{issue.topic}</h3>
                {issue.note && (
                  <p className="legal-statement">
                    <BasisChip basis={issue.basis} /> {issue.note}
                  </p>
                )}
                {issue.sources.length ? (
                  <>
                  <p className="legal-sublabel">Potentially relevant sources</p>
                  <ul className="legal-source-notes">
                    {issue.sources.map((n) => {
                      const source = sourcesById.get(n.sourceId);
                      if (!source) return null;
                      return (
                        <li key={n.sourceId}>
                          <p className="legal-source-head">
                            <span className={`type-badge legal-type legal-type--${source.type.toLowerCase()}`}>
                              {SOURCE_TYPE_LABEL[source.type]}
                            </span>
                            <SourceLink source={source} />
                            <span className="muted small">{source.domain}</span>
                          </p>
                          <p className="legal-statement">
                            <BasisChip basis={n.whatItSays ? n.whatItSaysBasis : "SOURCE_BACKED"} />{" "}
                            {n.whatItSays ?? `Excerpt: “${source.excerpt}”`}
                          </p>
                          {n.relevance && (
                            <p className="legal-statement small">
                              <BasisChip basis={n.relevanceBasis} /> {n.relevance}
                            </p>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                  </>
                ) : (
                  <p className="muted small">No retrieved official source addressed this topic.</p>
                )}
              </div>
            ))}
          </div>
        ) : (
          <Empty>No topics were identified.</Empty>
        )}
      </Section>

      <Section id="missing-heading" title="Missing information: questions to ask">
        {result.missingInformation.length ? (
          <ul className="legal-list legal-missing">
            {result.missingInformation.map((m, i) => (
              <li key={i}>
                <strong>{m.item}</strong>
                {m.whyItMatters && <span className="muted"> — {m.whyItMatters}</span>}
              </li>
            ))}
          </ul>
        ) : (
          <Empty>
            {outsideUs
              ? "Not assessed for situations outside the United States."
              : "Nothing obvious is missing, but a professional will likely have more questions."}
          </Empty>
        )}
      </Section>

      <Section id="uncertain-heading" title="Uncertainties">
        {result.uncertainties.length ? (
          <ul className="limitations">
            {result.uncertainties.map((u, i) => (
              <li key={i}>{u}</li>
            ))}
          </ul>
        ) : (
          <Empty>None reported.</Empty>
        )}
        <p className="standing-note">
          Facts are the person&apos;s own account and have not been verified. Sources describe the law in general;
          whether and how they apply depends on details a licensed attorney would need to review.
        </p>
      </Section>

      <Section id="sources-heading" title="Sources">
        {matched.length ? (
          <SourceList sources={matched} />
        ) : (
          <Empty>No retrieved source was matched to a topic.</Empty>
        )}
        {unmatched.length > 0 && (
          <details className="collapsible">
            <summary>
              {unmatched.length} other source{unmatched.length === 1 ? "" : "s"} retrieved but not matched to a topic
            </summary>
            <div className="collapsible-body">
              <SourceList sources={unmatched} />
            </div>
          </details>
        )}
        <p className="muted small">
          {searched} Took {formatDuration(result.durationMs)}.
        </p>
      </Section>

      <p className="legal-notice legal-notice--end" role="note">
        <strong>{result.notice}</strong>
      </p>
    </article>
  );
}
