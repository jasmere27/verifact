import { useState } from "react";
import InsightsTab from "../student/InsightsTab";
import type { Project } from "./types";

interface Row {
  id: string | null;
  statement: string;
  sourceKeys: string[];
}

const MAX_GAPS = 20;

/**
 * The research gap in the student's own words (with the sources it rests on), next to the AI's possible gaps
 * and framework from the saved abstracts, which are labelled as interpretation. We never write the gap for them.
 */
export default function GapsTab({
  project,
  onSave,
  onGenerate,
}: {
  project: Project;
  onSave: (rows: Row[]) => Promise<void>;
  onGenerate: () => Promise<void>;
}) {
  const [rows, setRows] = useState<Row[]>(() => (project.gaps.length ? project.gaps : [{ id: null, statement: "", sourceKeys: [] }]));
  const [saving, setSaving] = useState(false);
  const clean = (r: Row[]) => r.filter((x) => x.statement.trim()).map((x) => [x.id, x.statement.trim(), [...x.sourceKeys].sort()]);
  const dirty = JSON.stringify(clean(rows)) !== JSON.stringify(clean(project.gaps));

  async function save() {
    setSaving(true);
    try {
      await onSave(rows.filter((r) => r.statement.trim()).map((r) => ({ ...r, statement: r.statement.trim() })));
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <section className="report-section" aria-labelledby="pj-gap-heading">
        <h2 id="pj-gap-heading">Your research gap</h2>
        <p className="muted small">
          What do existing studies not cover that your study will? Write it yourself and tick the saved sources that show it.
          Use the possible gaps below as prompts to think with, not text to copy.
        </p>
        <ol className="pj-gaps">
          {rows.map((r, i) => (
            <li key={r.id ?? `new-${i}`} className="pj-gap">
              <label className="visually-hidden" htmlFor={`pj-gap-${i}`}>
                Gap statement {i + 1}
              </label>
              <textarea
                id={`pj-gap-${i}`}
                className="text-input"
                rows={3}
                maxLength={1500}
                value={r.statement}
                placeholder="e.g. Most studies on flipped learning are from US colleges; few examine public senior high schools in the Philippines."
                onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, statement: e.target.value } : x)))}
              />
              {project.library.length > 0 && (
                <details className="pj-gap-sources">
                  <summary>
                    Based on {r.sourceKeys.length} saved source{r.sourceKeys.length === 1 ? "" : "s"}
                  </summary>
                  {project.library.map((item) => (
                    <label key={item.key} className="pj-check pj-check--block">
                      <input
                        type="checkbox"
                        checked={r.sourceKeys.includes(item.key)}
                        onChange={(e) =>
                          setRows(
                            rows.map((x, j) =>
                              j === i
                                ? { ...x, sourceKeys: e.target.checked ? [...x.sourceKeys, item.key] : x.sourceKeys.filter((k) => k !== item.key) }
                                : x,
                            ),
                          )
                        }
                      />
                      {item.source.title} {item.source.year ? `(${item.source.year})` : ""}
                    </label>
                  ))}
                </details>
              )}
              <button type="button" className="text-button" onClick={() => setRows(rows.filter((_, j) => j !== i))}>
                Remove
              </button>
            </li>
          ))}
        </ol>
        <div className="row">
          <button
            type="button"
            className="button button--secondary button--small"
            disabled={rows.length >= MAX_GAPS}
            onClick={() => setRows([...rows, { id: null, statement: "", sourceKeys: [] }])}
          >
            Add another gap
          </button>
          {dirty ? (
            <button type="button" className="button button--primary button--small" disabled={saving} onClick={() => void save()}>
              {saving ? "Saving…" : "Save gap statements"}
            </button>
          ) : (
            <span className="muted small">Saved ✓</span>
          )}
        </div>
      </section>

      <InsightsTab
        insights={project.insights}
        sources={project.library.map((i) => ({ key: i.key, title: i.source.title }))}
        country={project.country}
        canEdit
        onGenerate={onGenerate}
      />
    </>
  );
}
