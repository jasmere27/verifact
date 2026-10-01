import { useState } from "react";
import { citeAll } from "../citations";
import type { CitationStyle } from "../citations";
import { IMAGE_SEARCH, lessonFor } from "../learn";
import { byTypePriority, isSocial } from "../sources";
import type { VerificationResult } from "../types";

/** Warning signs in the post and the steps a fact-checker would take, for learners. */
export function CheckItYourself({ result }: { result: VerificationResult }) {
  const { signs, steps } = lessonFor(result);
  return (
    <section className="report-section learn" aria-labelledby="learn-heading">
      <h2 id="learn-heading">How to check it yourself</h2>
      {signs.length > 0 && (
        <>
          <h3 className="learn-subhead">Warning signs in this post</h3>
          <ul className="learn-signs">
            {signs.map((s) => (
              <li key={s.id}>
                <strong>{s.title}.</strong> {s.why}
              </li>
            ))}
          </ul>
          <p className="muted small">Warning signs are reasons to look closer, not proof. The verdict above comes from the sources.</p>
        </>
      )}
      <h3 className="learn-subhead">What a fact-checker would do</h3>
      <ol className="learn-steps">
        {steps.map((step) => (
          <li key={step.id}>
            {step.text}
            {step.imageSearch && (
              <span className="learn-links">
                {IMAGE_SEARCH.map((site) => (
                  <a key={site.name} href={site.href} target="_blank" rel="noopener noreferrer" className="button button--secondary button--small">
                    {site.name}
                    <span className="visually-hidden"> (opens in a new tab)</span>
                  </a>
                ))}
              </span>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}

/** The report's sources as an APA or MLA reference list to copy into an essay. */
export function CiteSources({ result }: { result: VerificationResult }) {
  const [style, setStyle] = useState<CitationStyle>("APA");
  const [copied, setCopied] = useState<number | "all" | null>(null);
  const sources = byTypePriority(result.evidence.filter((e) => !isSocial(e) && e.url));
  if (sources.length === 0) return null;
  const citations = citeAll(sources, style);

  async function copy(text: string, which: number | "all") {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(which);
      setTimeout(() => setCopied((c) => (c === which ? null : c)), 2000);
    } catch {
      setCopied(null);
    }
  }

  return (
    <section className="report-section cite" aria-labelledby="cite-heading">
      <h2 id="cite-heading">Cite these sources</h2>
      <p className="muted small">
        For your essay, cite the original sources, not VeriFact. Open each one to check the title, date and author before you use it.
      </p>
      <div className="cite-bar">
        <div className="mode-tabs" role="radiogroup" aria-label="Citation style">
          {(["APA", "MLA"] as const).map((s) => (
            <button key={s} type="button" role="radio" className="mode-tab" aria-checked={style === s} onClick={() => setStyle(s)}>
              {s === "APA" ? "APA 7" : "MLA 9"}
            </button>
          ))}
        </div>
        <button type="button" className="button button--secondary button--small" onClick={() => void copy(citations.join("\n\n"), "all")}>
          {copied === "all" ? "Copied" : `Copy all ${citations.length}`}
        </button>
      </div>
      <ol className="cite-list">
        {citations.map((c, i) => (
          <li key={c}>
            <span className="cite-text">{c}</span>
            <button type="button" className="text-button" onClick={() => void copy(c, i)} aria-label={`Copy citation ${i + 1}`}>
              {copied === i ? "Copied" : "Copy"}
            </button>
          </li>
        ))}
      </ol>
      <div className="visually-hidden" aria-live="polite">
        {copied === null ? "" : copied === "all" ? "All citations copied." : "Citation copied."}
      </div>
    </section>
  );
}
