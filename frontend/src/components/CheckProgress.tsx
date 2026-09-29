import { useEffect, useRef, useState } from "react";
import { excerpt, plural } from "../format";
import type { SourcesFound, StageId } from "../types";
import type { Submission } from "./CheckForm";

interface Stage {
  id: StageId;
  title: string;
  detail: string;
}

const LINK_ONLY = /^https?:\/\/\S+$/i;

function stagesFor(submission: Submission): Stage[] {
  let reading: Stage | null = null;
  if (submission.mode === "text") {
    if (LINK_ONLY.test(submission.text.trim())) {
      reading = { id: "READING_INPUT", title: "Opening the link", detail: "Fetching the article at that address." };
    }
  } else if (submission.mode === "image") {
    reading = { id: "READING_INPUT", title: "Reading the image", detail: "Pulling the text out of your screenshot or photo." };
  } else {
    reading = { id: "READING_INPUT", title: "Transcribing the audio", detail: "Turning the speech into text." };
  }
  const rest: Stage[] = [
    { id: "EXTRACTING_CLAIMS", title: "Finding the claims", detail: "Picking out the specific statements that can be checked." },
    { id: "SEARCHING", title: "Searching the web", detail: "Looking for fact-checks, news and reference sources." },
    { id: "ASSESSING", title: "Weighing the evidence", detail: "Comparing what the sources say and noting what's uncertain." },
  ];
  return reading ? [reading, ...rest] : rest;
}

const ORDER: StageId[] = ["READING_INPUT", "EXTRACTING_CLAIMS", "SEARCHING", "ASSESSING"];

interface Props {
  submission: Submission;
  stage?: StageId;
  claims?: string[];
  sources?: SourcesFound;
  onCancel: () => void;
}

export default function CheckProgress({ submission, stage, claims, sources, onCancel }: Props) {
  const [elapsed, setElapsed] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    headingRef.current?.focus();
    const started = Date.now();
    const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const stages = stagesFor(submission);
  // The current step is the latest one the server reported; skipped steps count as done.
  const reported = stage ? ORDER.indexOf(stage) : -1;
  let current = stages.findIndex((s) => ORDER.indexOf(s.id) >= reported);
  if (current === -1) current = stages.length - 1;
  if (reported === -1) current = 0;
  const currentTitle = stages[current]?.title ?? "";

  return (
    <section className="progress card" aria-labelledby="progress-heading">
      <div className="progress-head">
        <h2 id="progress-heading" ref={headingRef} tabIndex={-1}>
          Checking the evidence…
        </h2>
        <span className="elapsed">
          <span className="visually-hidden">Elapsed: </span>
          {elapsed} s
        </span>
      </div>

      <div className="submitted">
        <span className="submitted-label">You submitted</span>
        {submission.mode === "text" ? (
          <p className="submitted-text">{excerpt(submission.text, 200)}</p>
        ) : (
          <p className="submitted-text">
            {submission.mode === "image" ? "Image" : "Audio"}: {submission.file.name}
          </p>
        )}
      </div>

      <ol className="stages">
        {stages.map((s, index) => {
          const state = index < current ? "done" : index === current ? "current" : "upcoming";
          return (
            <li key={s.id} className={`stage stage--${state}`} aria-current={state === "current" ? "step" : undefined}>
              <span className="stage-marker" aria-hidden="true" />
              <div className="stage-body">
                <span className="stage-title">
                  {s.title}
                  <span className="visually-hidden">
                    {state === "done" ? " (done)" : state === "current" ? " (in progress)" : " (next)"}
                  </span>
                </span>
                <span className="stage-detail">{s.detail}</span>
                {s.id === "EXTRACTING_CLAIMS" && claims && claims.length > 0 && (
                  <div className="stage-found">
                    <p className="stage-found-label">Checking {claims.length === 1 ? "this claim" : "these claims"}:</p>
                    <ul className="found-claims">
                      {claims.slice(0, 5).map((c, i) => (
                        <li key={i} className="found-claim">
                          {excerpt(c, 180)}
                        </li>
                      ))}
                      {claims.length > 5 && <li className="found-more">and {plural(claims.length - 5, "more claim")}</li>}
                    </ul>
                  </div>
                )}
                {s.id === "SEARCHING" && sources && (
                  <div className="stage-found">
                    <p className="stage-found-label">
                      Found {plural(sources.count, "source")}
                      {sources.domains.length > 0 ? ":" : ""}
                    </p>
                    {sources.domains.length > 0 && (
                      <p className="found-domains">
                        {sources.domains.slice(0, 6).join(", ")}
                        {sources.domains.length > 6 ? " …" : ""}
                      </p>
                    )}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ol>

      <div className="visually-hidden" aria-live="polite" aria-atomic="true">
        {currentTitle}
        {stage === "SEARCHING" && sources ? `. Found ${plural(sources.count, "source")}.` : ""}
      </div>

      <div className="progress-foot">
        <button type="button" className="button button--quiet" onClick={onCancel}>
          Cancel
        </button>
        <p className="progress-note">
          Usually 10–40 seconds.
          {elapsed >= 45 && " This one is taking longer than usual, but it's still working."}
        </p>
      </div>
    </section>
  );
}
