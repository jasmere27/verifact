import { useState } from "react";
import { safeHttpUrl } from "../../format";
import { FOLDERS } from "../student/categories";
import type { Category, Folder, FoundSource } from "../student/types";
import { questionLabel } from "./types";
import type { Aspect, PaperAnalysis, Project } from "./types";

const MATCH: Record<PaperAnalysis["match"]["status"], { label: string; tone: string }> = {
  VERIFIED: { label: "Verified record", tone: "ok" },
  POSSIBLE: { label: "Possible match: check it's this paper", tone: "warn" },
  NOT_FOUND: { label: "No record found", tone: "warn" },
  LOOKUP_FAILED: { label: "Couldn't check the database", tone: "muted" },
};

const ASPECT: Record<Aspect, string> = {
  DESIGN: "Design",
  PARTICIPANTS: "Participants",
  SETTING: "Setting",
  INSTRUMENTS: "Instruments",
  ANALYSIS: "Analysis",
};

/** An uploaded research paper: the index record, then the AI's plain reading, every point backed by the paper's own words. */
export default function PaperView({
  project,
  paper,
  onAdd,
  onSearch,
}: {
  project: Project;
  paper: PaperAnalysis;
  onAdd: (source: FoundSource, folder: Folder, questionId: string | null) => void;
  onSearch: (category: Category, text: string, label: string) => void;
}) {
  const { match } = paper;
  const s = match.source;
  const saved = s ? project.library.some((i) => i.key.toLowerCase() === s.key.toLowerCase()) : false;
  const [folder, setFolder] = useState<Folder>("RRS");
  const [question, setQuestion] = useState("");
  const href = s?.url ? safeHttpUrl(s.url) : null;

  return (
    <div className="pv">
      <section className="pv-record" aria-label="Database record">
        <span className={`st-badge st-badge--${MATCH[match.status].tone}`}>{MATCH[match.status].label}</span>
        {s ? (
          <>
            <p className="st-title">
              {href ? (
                <a href={href} target="_blank" rel="noopener noreferrer nofollow">
                  {s.title}
                </a>
              ) : (
                s.title
              )}
            </p>
            <p className="muted small">
              {[s.authors.join(", "), s.year, s.venue].filter(Boolean).join(" · ")}
              {s.doi ? ` · doi:${s.doi}` : ""}
            </p>
            {s.retracted && <p className="st-badge st-badge--bad">Retracted: don&apos;t cite as evidence</p>}
          </>
        ) : null}
        <p className="muted small">{match.basis}</p>
        {s &&
          (saved ? (
            <p className="small">In your library ✓</p>
          ) : (
            <div className="pv-add">
              <select className="st-select" aria-label="Section" value={folder} onChange={(e) => setFolder(e.target.value as Folder)}>
                {FOLDERS.map((f) => (
                  <option key={f.value} value={f.value}>
                    {f.label}
                  </option>
                ))}
              </select>
              {project.questions.length > 0 && (
                <select className="st-select" aria-label="Research question" value={question} onChange={(e) => setQuestion(e.target.value)}>
                  <option value="">No research question</option>
                  {project.questions.map((q) => (
                    <option key={q.id} value={q.id}>
                      Link to {questionLabel(project.questions, q.id)}
                    </option>
                  ))}
                </select>
              )}
              <button type="button" className="button button--primary button--small" onClick={() => onAdd(s, folder, question || null)}>
                Add to library
              </button>
            </div>
          ))}
      </section>

      {paper.plainSummary && (
        <section className="pv-section">
          <h3>
            In plain words <span className="pv-ai">AI explanation</span>
          </h3>
          <p>{paper.plainSummary}</p>
        </section>
      )}

      {paper.findings.length > 0 && (
        <section className="pv-section">
          <h3>Key findings</h3>
          <ul className="pv-list">
            {paper.findings.map((f) => (
              <li key={f.quote}>
                <p className="pv-statement">{f.statement}</p>
                <p className="st-quote">Paper: “{f.quote}”</p>
                <div className="pv-actions">
                  <button type="button" className="text-button" onClick={() => onSearch("SUPPORTING", f.statement, "Studies that support this finding")}>
                    Supporting studies
                  </button>
                  <button type="button" className="text-button" onClick={() => onSearch("CONTRADICTING", f.statement, "Studies that contradict this finding")}>
                    Contradicting studies
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      {paper.method.length > 0 && (
        <section className="pv-section">
          <h3>How the study was done</h3>
          <dl className="pv-method">
            {paper.method.map((m) => (
              <div key={m.aspect}>
                <dt>{ASPECT[m.aspect]}</dt>
                <dd>
                  {m.statement}
                  <span className="st-quote">Paper: “{m.quote}”</span>
                </dd>
              </div>
            ))}
          </dl>
        </section>
      )}

      {paper.authorLimitations.length > 0 && (
        <section className="pv-section">
          <h3>Limitations the authors state</h3>
          <ul className="pv-list">
            {paper.authorLimitations.map((l) => (
              <li key={l.quote}>
                <p className="pv-statement">{l.statement}</p>
                <p className="st-quote">Paper: “{l.quote}”</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      {paper.limitations.length > 0 && (
        <ul className="limitations">
          {paper.limitations.map((l) => (
            <li key={l}>{l}</li>
          ))}
        </ul>
      )}
      <p className="research-disclaimer">{paper.notice} Write your RRL in your own words and cite the paper, not this summary.</p>
    </div>
  );
}
