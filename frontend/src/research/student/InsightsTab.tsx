import { useState } from "react";
import { errorMessage } from "../../api";
import { formatDateTime } from "../../format";
import { generateInsights } from "./api";
import type { GapKind, RelationKind, Role, Workspace } from "./types";

const GAP_LABEL: Record<GapKind, string> = {
  POPULATION: "Population",
  SETTING: "Setting",
  METHOD: "Method",
  VARIABLE: "Variable",
  TIME: "Time period",
  EVIDENCE: "Evidence",
  OTHER: "Other",
};

const RELATION_LABEL: Record<RelationKind, string> = {
  SAME_FOCUS: "Same focus as yours",
  SAME_METHOD: "Method you could follow",
  DIFFERENT_SETTING: "Same question, different setting",
  SUPPORTS: "Findings in line with your expectation",
  CONTRADICTS: "Findings that differ",
  BACKGROUND: "Background",
};

const ROLE_LABEL: Record<Role, string> = {
  INDEPENDENT: "Independent variable",
  DEPENDENT: "Dependent variable",
  MEDIATOR: "Mediator",
  MODERATOR: "Moderator",
  CONTEXT: "Context",
};

export default function InsightsTab({ ws, token, onWorkspace }: { ws: Workspace; token: string | null; onWorkspace: (w: Workspace) => void }) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const insights = ws.insights;
  const titles = new Map(ws.sources.map((s) => [s.key, s.source.title]));
  const title = (key: string) => titles.get(key) ?? key;

  async function run() {
    if (!token) return;
    setBusy(true);
    setProblem(null);
    try {
      onWorkspace(await generateInsights(ws.id, token));
    } catch (err) {
      setProblem(errorMessage(err).message);
    } finally {
      setBusy(false);
    }
  }

  const button = token && (
    <div className="row">
      <button type="button" className="button button--primary button--small" disabled={busy || ws.sources.length < 3} onClick={() => void run()}>
        {busy ? "Reading your saved sources… (about 30–60 seconds)" : insights ? "Update insights" : "Generate insights"}
      </button>
      {ws.sources.length < 3 && <span className="muted small">Save at least 3 sources first.</span>}
    </div>
  );

  return (
    <section className="report-section st-insights" aria-label="Insights">
      <p className="muted small">
        Possible research gaps, how each saved study relates to yours, and variables for your conceptual framework, all read
        from the abstracts of the sources you saved.
      </p>
      {button}
      {problem && (
        <div className="alert" role="alert">
          <p>{problem}</p>
        </div>
      )}

      {insights && (
        <>
          <p className="muted small">
            Generated {formatDateTime(insights.generatedAt)} from {insights.basedOnSources} saved sources with abstracts.
          </p>

          <div className="st-block">
            <h2>What your saved sources cover</h2>
            <ul className="st-stats">
              <li>
                <strong>{insights.coverage.total}</strong> saved
              </li>
              {ws.country && (
                <>
                  <li>
                    <strong>{insights.coverage.local}</strong> local
                  </li>
                  <li>
                    <strong>{insights.coverage.foreign}</strong> foreign
                  </li>
                </>
              )}
              <li>
                <strong>{insights.coverage.lastFiveYears}</strong> from the last 5 years
              </li>
              <li>
                <strong>{insights.coverage.oldestYear ? `${insights.coverage.oldestYear}–${insights.coverage.newestYear}` : "n/a"}</strong> years
              </li>
              <li>
                <strong>{insights.coverage.reviews}</strong> reviews
              </li>
            </ul>
            <p className="muted small">Counted from the records, not by AI.</p>
          </div>

          <div className="st-block">
            <h2>Possible research gaps</h2>
            {insights.gaps.length === 0 ? (
              <p className="muted small">No gap could be tied to your saved sources. Save more sources and try again.</p>
            ) : (
              <ul className="st-list">
                {insights.gaps.map((g) => (
                  <li key={g.statement} className="st-card">
                    <div className="st-card-badges">
                      <span className="st-badge st-badge--warn">AI interpretation</span>
                      <span className="st-badge st-badge--muted">{GAP_LABEL[g.kind]}</span>
                    </div>
                    <p>{g.statement}</p>
                    <p className="muted small">Based on: {g.basis.map(title).join("; ")}</p>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="st-block">
            <h2>Variables for your conceptual framework</h2>
            {insights.framework.length === 0 ? (
              <p className="muted small">None suggested.</p>
            ) : (
              <ul className="st-list">
                {insights.framework.map((v) => (
                  <li key={v.name} className="st-card">
                    <div className="st-card-badges">
                      <span className={`st-badge ${v.verification === "VERIFIED" ? "st-badge--ok" : "st-badge--warn"}`}>
                        {v.verification === "VERIFIED" ? "Named in your sources" : "Unverified suggestion"}
                      </span>
                      <span className="st-badge st-badge--muted">{ROLE_LABEL[v.role]}</span>
                    </div>
                    <p className="st-title">{v.name}</p>
                    {v.sourceKeys.length > 0 ? (
                      <p className="muted small">Named in: {v.sourceKeys.map(title).join("; ")}</p>
                    ) : (
                      <p className="muted small">None of your saved sources names it. Find sources for it before using it.</p>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="st-block">
            <h2>How your saved studies relate to yours</h2>
            {insights.relations.length === 0 ? (
              <p className="muted small">No relation could be backed by an abstract quote.</p>
            ) : (
              <ul className="st-list">
                {insights.relations.map((r) => (
                  <li key={r.key} className="st-card">
                    <div className="st-card-badges">
                      <span className={`st-badge ${r.kind === "CONTRADICTS" ? "st-badge--bad" : "st-badge--muted"}`}>{RELATION_LABEL[r.kind]}</span>
                    </div>
                    <p className="st-title">{r.title}</p>
                    <p>{r.how}</p>
                    <p className="st-quote">Abstract: “{r.quote}”</p>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {insights.limitations.length > 0 && (
            <ul className="limitations">
              {insights.limitations.map((l) => (
                <li key={l}>{l}</li>
              ))}
            </ul>
          )}
          <p className="research-disclaimer">{insights.notice}</p>
        </>
      )}
    </section>
  );
}
