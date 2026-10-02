import { useState } from "react";
import type { TitleSource } from "./api";
import { questionLabel } from "./types";
import type { NextStep, Project } from "./types";

const ACTION_LABEL: Record<NextStep["action"], string> = {
  ADD_QUESTIONS: "Add your questions",
  FIND_SOURCES: "Find studies",
  LINK_SOURCES: "Match them to questions",
  OPEN_LIBRARY: "Open saved studies",
  REVIEW_RETRACTED: "Review them",
  WRITE_GAP: "Write it",
  GENERATE_INSIGHTS: "See what's not yet studied",
  UPLOAD_DRAFT: "Upload a chapter",
  REVIEW_DRAFT_CLAIMS: "Show statements",
  CHECK_CITATIONS: "Check references",
  OPEN_FILES: "Add to saved studies",
  REVIEW_LINKS: "Review matches",
};

/**
 * The project's Home: after "Start from your Chapter 1", a card to check what was filled in; then one "Do this next"
 * card (the server's top step), the rest folded under "Later", and progress.
 */
export default function HomeTab({
  project,
  setup,
  onSetupDone,
  onRename,
  onEditQuestions,
  onAction,
}: {
  project: Project;
  /** How the project was just set up from a file, or null. */
  setup: TitleSource | null;
  onSetupDone: () => void;
  onRename: (title: string) => Promise<void>;
  onEditQuestions: () => void;
  onAction: (step: NextStep) => void;
}) {
  const { progress, nextSteps } = project;
  const [first, ...later] = nextSteps;
  return (
    <section className="report-section pj-home" aria-label="Home">
      {setup && <SetupCheck project={project} setup={setup} onDone={onSetupDone} onRename={onRename} onEditQuestions={onEditQuestions} />}

      <div className="pj-next-card" data-tour="next">
        <p className="pj-next-kicker">Do this next</p>
        {first ? (
          <>
            <p className="pj-next-title">{first.title}</p>
            <p className="pj-next-detail">{first.detail}</p>
            <button type="button" className="button button--primary pj-next-button" onClick={() => onAction(first)}>
              {ACTION_LABEL[first.action]} →
            </button>
          </>
        ) : (
          <>
            <p className="pj-next-title">You&apos;re on track</p>
            <p className="pj-next-detail">Keep reading your saved studies and adding what they found. Come back after you update a chapter.</p>
          </>
        )}
      </div>

      {later.length > 0 && (
        <details className="pj-later">
          <summary>Later ({later.length})</summary>
          <ol className="pj-steps">
            {later.map((s) => (
              <li key={s.id} className="pj-step">
                <div className="pj-step-body">
                  <p className="pj-step-title">{s.title}</p>
                  <p className="pj-step-detail">{s.detail}</p>
                </div>
                <button type="button" className="button button--secondary button--small" onClick={() => onAction(s)}>
                  {ACTION_LABEL[s.action]}
                </button>
              </li>
            ))}
          </ol>
        </details>
      )}

      <div className="pj-progress" data-tour="progress">
        <div className="pj-progress-head">
          <h2 id="pj-progress-heading">Your progress</h2>
          <strong>{progress.percent}%</strong>
        </div>
        <div className="pj-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress.percent} aria-labelledby="pj-progress-heading">
          <span style={{ width: `${progress.percent}%` }} />
        </div>
        <details>
          <summary className="small">See the checklist</summary>
          <ul className="pj-milestones">
            {progress.milestones.map((m) => (
              <li key={m.id} className={m.done ? "is-done" : undefined}>
                <span className="pj-tick" aria-hidden="true">
                  {m.done ? "✓" : ""}
                </span>
                <span>
                  {m.label}
                  <span className="visually-hidden">{m.done ? " (done)" : " (not yet)"}</span>
                  {m.detail && <span className="muted small"> · {m.detail}</span>}
                </span>
              </li>
            ))}
          </ul>
        </details>
      </div>
      <p className="muted small">
        Next steps are worked out from what&apos;s in your project, not guessed by AI. Every study you save is a real record.
      </p>
    </section>
  );
}

const TITLE_NOTE: Record<TitleSource, string> = {
  DOCUMENT: "Taken from your file.",
  SUGGESTED: "Your file had no title page, so this is a suggested working title. Change it if it's not right.",
  NONE: "We couldn't find your title. Type it here.",
};

/** "We set up your project from your file": the student checks the title and questions before going on. */
function SetupCheck({
  project,
  setup,
  onDone,
  onRename,
  onEditQuestions,
}: {
  project: Project;
  setup: TitleSource;
  onDone: () => void;
  onRename: (title: string) => Promise<void>;
  onEditQuestions: () => void;
}) {
  const [title, setTitle] = useState(project.title);
  const [saving, setSaving] = useState(false);
  const changed = title.trim() !== project.title && title.trim().length >= 5;
  const draft = project.files.find((f) => f.kind === "DRAFT");
  const withAnswers = project.questions.filter((q) => q.hypothesis).length;

  async function rename() {
    setSaving(true);
    try {
      await onRename(title.trim());
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="pj-setup card" data-tour="setup">
      <p className="pj-next-kicker">We read your file</p>
      <h2 className="pj-setup-heading">Check that this is right</h2>

      <label htmlFor="pj-setup-title" className="pj-hyp-label">
        Title
      </label>
      <div className="pj-setup-row">
        <textarea id="pj-setup-title" className="text-input" rows={2} value={title} maxLength={300} onChange={(e) => setTitle(e.target.value)} />
        {changed && (
          <button type="button" className="button button--secondary button--small" disabled={saving} onClick={() => void rename()}>
            {saving ? "Saving…" : "Save"}
          </button>
        )}
      </div>
      <p className={`small${setup === "DOCUMENT" ? " muted" : " pj-setup-warn"}`}>{TITLE_NOTE[setup]}</p>

      <p className="pj-hyp-label">Research questions</p>
      {project.questions.length > 0 ? (
        <ol className="pj-setup-questions">
          {project.questions.map((q) => (
            <li key={q.id}>
              <span className="pj-sg-rq">{questionLabel(project.questions, q.id)}</span> {q.text}
              {q.hypothesis && <span className="muted small"> · You expect: {q.hypothesis}</span>}
            </li>
          ))}
        </ol>
      ) : (
        <p className="small pj-setup-warn">We didn&apos;t find research questions (usually under &ldquo;Statement of the Problem&rdquo;). Add them so we can find studies for each one.</p>
      )}
      {project.questions.length > 0 && (
        <p className="muted small">
          Copied word for word from your file.
          {withAnswers < project.questions.length && " Add what you expect to find for each question to get supporting and conflicting studies."}
        </p>
      )}
      {draft && draft.needsCitation != null && draft.needsCitation > 0 && (
        <p className="small">
          We also found <strong>{draft.needsCitation} statement{draft.needsCitation === 1 ? "" : "s"}</strong> in it that may need a citation.
          You&apos;ll see them in My paper.
        </p>
      )}
      <div className="row">
        <button type="button" className="button button--primary button--small" onClick={onDone}>
          Looks right
        </button>
        <button type="button" className="button button--secondary button--small" onClick={onEditQuestions}>
          {project.questions.length ? "Edit questions" : "Add questions"}
        </button>
      </div>
    </div>
  );
}
