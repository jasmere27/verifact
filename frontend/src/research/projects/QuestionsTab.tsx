import { useState } from "react";
import LinkSuggestions from "./LinkSuggestions";
import type { SuggestResult } from "./api";
import { GROUP_LABEL, GROUP_ORDER, groupOf } from "./links";
import type { Group } from "./links";
import type { LibraryItem, Project, Question } from "./types";

const MAX_QUESTIONS = 10;
const RECENT_YEARS = 5;

interface Row {
  id: string | null;
  text: string;
  hypothesis: string;
}

export type FindKind = "studies" | "supporting" | "conflicting";

/** Research questions, with how much of the library supports each one and what those sources say. */
export default function QuestionsTab({
  project,
  onSave,
  onFind,
  onOpenLibrary,
  onSuggest,
  onReview,
}: {
  project: Project;
  onSave: (rows: Row[]) => Promise<void>;
  onFind: (question: Question, kind: FindKind) => void;
  onOpenLibrary: (questionId: string) => void;
  onSuggest: () => Promise<SuggestResult | null>;
  onReview: (key: string, questionId: string, accept: boolean) => Promise<void>;
}) {
  const [rows, setRows] = useState<Row[]>(() =>
    project.questions.length
      ? project.questions.map((q) => ({ id: q.id, text: q.text, hypothesis: q.hypothesis ?? "" }))
      : [{ id: null, text: "", hypothesis: "" }],
  );
  const [saving, setSaving] = useState(false);
  const clean = (list: Row[]) => list.filter((r) => r.text.trim()).map((r) => ({ id: r.id, text: r.text.trim(), hypothesis: r.hypothesis.trim() }));
  const dirty =
    JSON.stringify(clean(rows).map((r) => [r.id, r.text, r.hypothesis])) !==
    JSON.stringify(project.questions.map((q) => [q.id, q.text, q.hypothesis ?? ""]));
  const year = new Date().getFullYear();

  async function save() {
    setSaving(true);
    try {
      await onSave(clean(rows));
    } finally {
      setSaving(false);
    }
  }

  const update = (i: number, patch: Partial<Row>) => setRows(rows.map((x, j) => (j === i ? { ...x, ...patch } : x)));

  return (
    <>
      <section className="report-section" aria-labelledby="pj-q-heading">
        <h2 id="pj-q-heading">Research questions</h2>
        <p className="muted small">
          Write them in your own words. Good questions are specific (who, what, where) and answerable with data you can
          collect. Add what you expect to find, and we can look for studies that support it or point the other way.
        </p>
        <ol className="pj-questions">
          {rows.map((r, i) => {
            const saved = r.id ? project.questions.find((q) => q.id === r.id) : undefined;
            const linked = saved ? project.library.filter((item) => item.questionIds.includes(saved.id)) : [];
            const local = linked.filter((item) => item.source.local).length;
            const recent = linked.filter((item) => item.source.year != null && item.source.year > year - RECENT_YEARS).length;
            return (
              <li key={r.id ?? `new-${i}`} className="pj-question">
                <label className="visually-hidden" htmlFor={`pj-q-${i}`}>
                  Research question {i + 1}
                </label>
                <textarea
                  id={`pj-q-${i}`}
                  className="text-input"
                  rows={2}
                  maxLength={400}
                  value={r.text}
                  placeholder={i === 0 ? "e.g. What is the effect of flipped learning on Grade 11 students' mathematics achievement?" : "Another research question"}
                  onChange={(e) => update(i, { text: e.target.value })}
                />
                <label className="pj-hyp-label" htmlFor={`pj-h-${i}`}>
                  What do you expect to find? <span className="muted">(optional)</span>
                </label>
                <textarea
                  id={`pj-h-${i}`}
                  className="text-input pj-hyp"
                  rows={2}
                  maxLength={600}
                  value={r.hypothesis}
                  placeholder="e.g. Flipped learning improves mathematics achievement."
                  onChange={(e) => update(i, { hypothesis: e.target.value })}
                />
                <div className="pj-question-foot">
                  {saved ? (
                    <>
                      <button type="button" className={`pj-coverage${linked.length < 3 ? " is-low" : ""}`} onClick={() => onOpenLibrary(saved.id)}>
                        RQ{i + 1}: {linked.length} source{linked.length === 1 ? "" : "s"}
                        {project.country ? ` · ${local} local` : ""} · {recent} recent
                      </button>
                      <button type="button" className="button button--secondary button--small" onClick={() => onFind(saved, "studies")}>
                        Find studies for RQ{i + 1}
                      </button>
                      {saved.hypothesis ? (
                        <>
                          <button type="button" className="text-button" onClick={() => onFind(saved, "supporting")}>
                            Supporting studies
                          </button>
                          <button type="button" className="text-button" onClick={() => onFind(saved, "conflicting")}>
                            Conflicting studies
                          </button>
                        </>
                      ) : null}
                    </>
                  ) : (
                    <span className="muted small">Save to start tracking sources for it.</span>
                  )}
                  <button type="button" className="text-button" onClick={() => setRows(rows.filter((_, j) => j !== i))}>
                    Remove
                  </button>
                </div>
                {saved && linked.length > 0 && <Evidence label={`RQ${i + 1}`} question={saved} linked={linked} />}
              </li>
            );
          })}
        </ol>
        <div className="row">
          <button
            type="button"
            className="button button--secondary button--small"
            disabled={rows.length >= MAX_QUESTIONS}
            onClick={() => setRows([...rows, { id: null, text: "", hypothesis: "" }])}
          >
            Add a question
          </button>
          {dirty ? (
            <button type="button" className="button button--primary button--small" disabled={saving} onClick={() => void save()}>
              {saving ? "Saving…" : "Save questions"}
            </button>
          ) : (
            <span className="muted small">Saved ✓</span>
          )}
        </div>
      </section>

      <LinkSuggestions project={project} onSuggest={onSuggest} onReview={onReview} />
    </>
  );
}

/** What the sources linked to one question say, grouped by how they help; disagreement is called out. */
function Evidence({ label, question, linked }: { label: string; question: Question; linked: LibraryItem[] }) {
  const groups = new Map<Group, { item: LibraryItem; how: string | null; quote: string | null }[]>();
  for (const item of linked) {
    const note = item.linkNotes.find((n) => n.questionId === question.id);
    const g = groupOf(note);
    groups.set(g, [...(groups.get(g) ?? []), { item, how: note?.how ?? null, quote: note?.quote ?? null }]);
  }
  const supports = groups.get("SUPPORTS")?.length ?? 0;
  const against = groups.get("CONTRADICTS")?.length ?? 0;
  return (
    <details className="pj-evidence">
      <summary>
        What your sources say about {label} ({linked.length})
      </summary>
      {supports > 0 && against > 0 && (
        <p className="pj-disagree">
          Studies disagree: {supports} support{supports === 1 ? "s" : ""} your expected answer and {against} point
          {against === 1 ? "s" : ""} the other way. Discuss both sides in your review of related literature, and look at
          how their samples, settings or methods differ.
        </p>
      )}
      {GROUP_ORDER.filter((g) => groups.has(g)).map((g) => (
        <div key={g} className={`pj-ev-group pj-ev-${g.toLowerCase()}`}>
          <h4>{GROUP_LABEL[g]}</h4>
          <ul>
            {groups.get(g)!.map(({ item, how, quote }) => (
              <li key={item.key}>
                <p className="pj-ev-title">
                  {item.source.title}
                  {item.source.year ? ` (${item.source.year})` : ""}
                </p>
                {how && (
                  <p className="small">
                    {how} <span className="pv-ai">AI reading</span>
                  </p>
                )}
                {quote && <p className="st-quote">Abstract: “{quote}”</p>}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </details>
  );
}
