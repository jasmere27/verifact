import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { ApiError, sendFeedback } from "../api";
import type { FeedbackReason } from "../api";

const MAX_COMMENT = 500;

const REASONS: { value: FeedbackReason; label: string }[] = [
  { value: "WRONG_VERDICT", label: "The verdict is wrong" },
  { value: "BAD_SOURCES", label: "The sources are poor" },
  { value: "MISSED_CLAIM", label: "It missed or misunderstood my claim" },
  { value: "OTHER", label: "Something else" },
];

/* Per-report memory that feedback was given; localStorage may be unavailable, so every access is guarded. */
const storageKey = (id: string) => `verifact.feedback.${id}`;

function alreadyRated(id: string): boolean {
  try {
    return window.localStorage.getItem(storageKey(id)) !== null;
  } catch {
    return false;
  }
}

function rememberRating(id: string, helpful: boolean) {
  try {
    window.localStorage.setItem(storageKey(id), JSON.stringify({ helpful, at: new Date().toISOString() }));
  } catch {
    // storage full or blocked: the prompt will simply reappear after reload
  }
}

type Step =
  | { kind: "ask" }
  | { kind: "details" }
  | { kind: "sending"; helpful: boolean }
  | { kind: "thanks" }
  | { kind: "rated" };

function errorText(err: unknown): string {
  if (err instanceof ApiError) {
    const wait =
      err.status === 429 && err.retryAfterSeconds ? ` You can try again in about ${err.retryAfterSeconds} seconds.` : "";
    return `${err.message}${wait}`;
  }
  return "Couldn't send your feedback. Please try again.";
}

export default function FeedbackPrompt({ reportId }: { reportId: string }) {
  const [step, setStep] = useState<Step>(() => (alreadyRated(reportId) ? { kind: "rated" } : { kind: "ask" }));
  const [reason, setReason] = useState<FeedbackReason | null>(null);
  const [comment, setComment] = useState("");
  const [error, setError] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const firstReasonRef = useRef<HTMLInputElement>(null);
  const noButtonRef = useRef<HTMLButtonElement>(null);
  const thanksRef = useRef<HTMLParagraphElement>(null);
  const focusAfterCancel = useRef(false);

  useEffect(() => () => controllerRef.current?.abort(), []);

  // Keep keyboard focus on something that still exists as the prompt changes shape.
  useEffect(() => {
    if (step.kind === "details") firstReasonRef.current?.focus();
    else if (step.kind === "thanks") thanksRef.current?.focus();
    else if (step.kind === "ask" && focusAfterCancel.current) {
      focusAfterCancel.current = false;
      noButtonRef.current?.focus();
    }
  }, [step.kind]);

  async function submit(helpful: boolean) {
    const back: Step = helpful ? { kind: "ask" } : { kind: "details" };
    const controller = new AbortController();
    controllerRef.current = controller;
    setError(null);
    setStep({ kind: "sending", helpful });
    try {
      const trimmed = comment.trim();
      await sendFeedback(
        reportId,
        helpful
          ? { helpful: true, reason: null, comment: null }
          : { helpful: false, reason, comment: trimmed ? trimmed.slice(0, MAX_COMMENT) : null },
        controller.signal,
      );
      if (controller.signal.aborted) return;
      rememberRating(reportId, helpful);
      setStep({ kind: "thanks" });
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(errorText(err));
      setStep(back);
    }
  }

  function onSubmitDetails(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    void submit(false);
  }

  function cancel() {
    setError(null);
    setReason(null);
    setComment("");
    focusAfterCancel.current = true;
    setStep({ kind: "ask" });
  }

  const sending = step.kind === "sending";
  const showDetails = step.kind === "details" || (sending && !step.helpful);

  return (
    <section className="report-section feedback" aria-labelledby="feedback-heading">
      <h2 id="feedback-heading">Your feedback</h2>

      {step.kind === "rated" && <p className="feedback-done">You rated this check. Thank you.</p>}

      {step.kind === "thanks" && (
        <p className="feedback-done" ref={thanksRef} tabIndex={-1} role="status">
          Thanks for the feedback.
        </p>
      )}

      {(step.kind === "ask" || (sending && step.helpful)) && (
        <div className="feedback-ask">
          <p className="feedback-question" id="feedback-question">
            Was this check helpful?
          </p>
          <div className="feedback-buttons" role="group" aria-labelledby="feedback-question">
            <button
              type="button"
              className="button button--secondary button--small"
              onClick={() => void submit(true)}
              disabled={sending}
            >
              <span aria-hidden="true">👍</span> {sending ? "Sending…" : "Yes"}
            </button>
            <button
              type="button"
              ref={noButtonRef}
              className="button button--secondary button--small"
              onClick={() => {
                setError(null);
                setStep({ kind: "details" });
              }}
              disabled={sending}
            >
              <span aria-hidden="true">👎</span> No
            </button>
          </div>
        </div>
      )}

      {showDetails && (
        <form className="feedback-form" onSubmit={onSubmitDetails} noValidate aria-label="What was wrong with this check">
          <fieldset className="feedback-reasons" disabled={sending}>
            <legend>What was wrong?</legend>
            {REASONS.map((r, i) => (
              <label key={r.value} className="feedback-reason">
                <input
                  type="radio"
                  name={`feedback-reason-${reportId}`}
                  value={r.value}
                  checked={reason === r.value}
                  onChange={() => setReason(r.value)}
                  ref={i === 0 ? firstReasonRef : undefined}
                />
                <span>{r.label}</span>
              </label>
            ))}
          </fieldset>

          <div className="feedback-comment">
            <label htmlFor={`feedback-comment-${reportId}`}>
              Anything else? <span className="muted">(optional)</span>
            </label>
            <textarea
              id={`feedback-comment-${reportId}`}
              className="text-input feedback-textarea"
              rows={3}
              maxLength={MAX_COMMENT}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              disabled={sending}
              aria-describedby={`feedback-count-${reportId}`}
            />
            <span id={`feedback-count-${reportId}`} className="counter">
              {comment.length}/{MAX_COMMENT}
              <span className="visually-hidden"> characters</span>
            </span>
          </div>

          <div className="feedback-actions">
            <button type="submit" className="button button--primary button--small" disabled={sending}>
              {sending ? "Sending…" : "Submit"}
            </button>
            <button type="button" className="button button--secondary button--small" onClick={cancel} disabled={sending}>
              Cancel
            </button>
          </div>
        </form>
      )}

      {error && (
        <p className="field-problem" role="alert">
          {error}
        </p>
      )}

      {step.kind !== "rated" && step.kind !== "thanks" && (
        <p className="muted small">Feedback is anonymous and helps improve VeriFact.</p>
      )}
    </section>
  );
}
