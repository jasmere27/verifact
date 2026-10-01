import { useState } from "react";
import type { Project, Question } from "./types";

const MAX_QUESTIONS = 10;
const RECENT_YEARS = 5;

interface Row {
  id: string | null;
  text: string;
}

/** Research questions, with how much of the library supports each one. */
export default function QuestionsTab({
  project,
  onSave,
  onFind,
  onOpenLibrary,
}: {
  project: Project;
  onSave: (rows: Row[]) => Promise<void>;
  onFind: (question: Question) => void;
  onOpenLibrary: (questionId: string) => void;
}) {
  const [rows, setRows] = useState<Row[]>(() => (project.questions.length ? project.questions : [{ id: null, text: "" }]));
  const [saving, setSaving] = useState(false);
  const dirty =
    JSON.stringify(rows.filter((r) => r.text.trim()).map((r) => [r.id, r.text.trim()])) !==
    JSON.stringify(project.questions.map((q) => [q.id, q.text]));
  const year = new Date().getFullYear();

  async function save() {
    setSaving(true);
    try {
      await onSave(rows.filter((r) => r.text.trim()).map((r) => ({ id: r.id, text: r.text.trim() })));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="report-section" aria-labelledby="pj-q-heading">
      <h2 id="pj-q-heading">Research questions</h2>
      <p className="muted small">
        Write them in your own words. Good questions are specific (who, what, where) and answerable with data you can
        collect. Your library and next steps are checked against them.
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
                onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, text: e.target.value } : x)))}
              />
              <div className="pj-question-foot">
                {saved ? (
                  <>
                    <button type="button" className={`pj-coverage${linked.length < 3 ? " is-low" : ""}`} onClick={() => onOpenLibrary(saved.id)}>
                      RQ{i + 1}: {linked.length} source{linked.length === 1 ? "" : "s"}
                      {project.country ? ` · ${local} local` : ""} · {recent} recent
                    </button>
                    <button type="button" className="button button--secondary button--small" onClick={() => onFind(saved)}>
                      Find studies for RQ{i + 1}
                    </button>
                  </>
                ) : (
                  <span className="muted small">Save to start tracking sources for it.</span>
                )}
                <button type="button" className="text-button" onClick={() => setRows(rows.filter((_, j) => j !== i))}>
                  Remove
                </button>
              </div>
            </li>
          );
        })}
      </ol>
      <div className="row">
        <button type="button" className="button button--secondary button--small" disabled={rows.length >= MAX_QUESTIONS} onClick={() => setRows([...rows, { id: null, text: "" }])}>
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
  );
}
