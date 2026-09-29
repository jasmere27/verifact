import { useEffect, useRef, useState } from "react";
import { excerpt } from "../format";
import type { Submission } from "./CheckForm";

interface Stage {
  title: string;
  detail: string;
  /** Seconds after submission when this step is expected to begin (approximate). */
  startsAt: number;
}

function stagesFor(mode: Submission["mode"]): Stage[] {
  const first: Stage =
    mode === "image"
      ? { title: "Reading the image", detail: "Extracting the text and pulling out specific factual claims.", startsAt: 0 }
      : mode === "audio"
        ? { title: "Transcribing the audio", detail: "Turning speech into text and pulling out specific factual claims.", startsAt: 0 }
        : { title: "Finding the claims", detail: "Pulling out the specific factual statements that can be checked.", startsAt: 0 };
  return [
    first,
    { title: "Searching sources", detail: "Looking for reporting and reference material on each claim.", startsAt: 6 },
    { title: "Weighing the evidence", detail: "Comparing what the sources say and noting what's uncertain.", startsAt: 16 },
  ];
}

interface Props {
  submission: Submission;
  onCancel: () => void;
}

export default function CheckProgress({ submission, onCancel }: Props) {
  const [elapsed, setElapsed] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    headingRef.current?.focus();
    const started = Date.now();
    const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const stages = stagesFor(submission.mode);
  let current = 0;
  stages.forEach((stage, index) => {
    if (elapsed >= stage.startsAt) current = index;
  });

  return (
    <section className="progress card" aria-labelledby="progress-heading">
      <h2 id="progress-heading" ref={headingRef} tabIndex={-1}>
        Checking the evidence…
      </h2>

      <div className="submitted">
        <span className="submitted-label">You submitted</span>
        {submission.mode === "text" ? (
          <p className="submitted-text">{excerpt(submission.text, 280)}</p>
        ) : (
          <p className="submitted-text">
            {submission.mode === "image" ? "Image" : "Audio"}: {submission.file.name}
          </p>
        )}
      </div>

      <ol className="stages">
        {stages.map((stage, index) => {
          const state = index < current ? "done" : index === current ? "current" : "upcoming";
          return (
            <li key={stage.title} className={`stage stage--${state}`} aria-current={state === "current" ? "step" : undefined}>
              <span className="stage-marker" aria-hidden="true" />
              <span className="stage-body">
                <span className="stage-title">
                  {stage.title}
                  <span className="visually-hidden">
                    {state === "done" ? " (expected to be done)" : state === "current" ? " (likely in progress)" : " (next)"}
                  </span>
                </span>
                <span className="stage-detail">{stage.detail}</span>
              </span>
            </li>
          );
        })}
      </ol>

      <p className="progress-note">
        These are the steps every check goes through. The service doesn&apos;t report live progress, so the highlighted
        step is an estimate. <span className="elapsed">{elapsed} s elapsed.</span>
        {elapsed >= 45 && " This one is taking longer than usual, but it's still working."}
      </p>

      <button type="button" className="button button--quiet" onClick={onCancel}>
        Cancel
      </button>
    </section>
  );
}
