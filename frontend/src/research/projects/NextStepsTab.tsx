import type { NextStep, Project } from "./types";

const ACTION_LABEL: Record<NextStep["action"], string> = {
  ADD_QUESTIONS: "Write questions",
  FIND_SOURCES: "Find studies",
  LINK_SOURCES: "Get link suggestions",
  OPEN_LIBRARY: "Open library",
  REVIEW_RETRACTED: "Review them",
  WRITE_GAP: "Write your gap",
  GENERATE_INSIGHTS: "Get gap insights",
  UPLOAD_DRAFT: "Upload draft",
  REVIEW_DRAFT_CLAIMS: "Show statements",
  CHECK_CITATIONS: "Check citations",
  OPEN_FILES: "Add to library",
  REVIEW_LINKS: "Review suggestions",
};

const PRIORITY_LABEL: Record<NextStep["priority"], string> = { HIGH: "Do this first", MEDIUM: "Next", LOW: "When you can" };

/** "What should I do next?" and the progress checklist, both computed from the project by the server. */
export default function NextStepsTab({ project, onAction }: { project: Project; onAction: (step: NextStep) => void }) {
  const { progress, nextSteps } = project;
  return (
    <section className="report-section pj-overview" aria-labelledby="pj-next-heading">
      <div className="pj-progress">
        <div className="pj-progress-head">
          <h2 id="pj-progress-heading">Your progress</h2>
          <strong>{progress.percent}%</strong>
        </div>
        <div className="pj-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress.percent} aria-labelledby="pj-progress-heading">
          <span style={{ width: `${progress.percent}%` }} />
        </div>
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
      </div>

      <h2 id="pj-next-heading">What should I do next?</h2>
      {nextSteps.length === 0 ? (
        <p className="pj-empty">Nothing urgent. Keep reading, add key findings to your library, and refine your gap statement.</p>
      ) : (
        <ol className="pj-steps">
          {nextSteps.map((s) => (
            <li key={s.id} className={`pj-step pj-step--${s.priority.toLowerCase()}`}>
              <div className="pj-step-body">
                <span className="pj-step-priority">{PRIORITY_LABEL[s.priority]}</span>
                <p className="pj-step-title">{s.title}</p>
                <p className="pj-step-detail">{s.detail}</p>
              </div>
              <button type="button" className="button button--secondary button--small" onClick={() => onAction(s)}>
                {ACTION_LABEL[s.action]}
              </button>
            </li>
          ))}
        </ol>
      )}
      <p className="muted small">
        Worked out from what&apos;s in your project (your questions, saved sources and draft), not guessed by AI. Sources
        it finds are real records you choose to save.
      </p>
    </section>
  );
}
