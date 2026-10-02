import { useEffect, useRef, useState } from "react";
import { markTourSeen } from "./tourStorage";

export interface TourStep {
  /** `data-tour` value of the element this step points at. */
  target: string;
  title: string;
  body: string;
}

/**
 * A short starter guide, like a game tutorial: each step highlights the real control on the page and says what it's
 * for. Skippable at any point; finishing or skipping remembers it in this browser.
 */
export default function Tour({ steps, onClose }: { steps: TourStep[]; onClose: () => void }) {
  const [index, setIndex] = useState(0);
  const panel = useRef<HTMLDivElement>(null);
  const step = steps[Math.min(index, steps.length - 1)];
  const last = index >= steps.length - 1;

  useEffect(() => {
    const el = document.querySelector<HTMLElement>(`[data-tour="${step.target}"]`);
    if (!el) return;
    el.classList.add("tour-target");
    // Keep the target in the top part of the screen: the guide's panel sits at the bottom.
    const top = el.getBoundingClientRect().top + window.scrollY - 150;
    window.scrollTo({ top: Math.max(0, top), behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
    return () => el.classList.remove("tour-target");
  }, [step.target]);

  useEffect(() => {
    panel.current?.focus();
  }, [index]);

  function close() {
    markTourSeen();
    onClose();
  }

  return (
    <div
      ref={panel}
      className="tour"
      role="dialog"
      aria-modal="false"
      aria-labelledby="tour-title"
      tabIndex={-1}
      onKeyDown={(e) => {
        if (e.key === "Escape") close();
      }}
    >
      <p className="tour-count">
        {index + 1} of {steps.length}
      </p>
      <p id="tour-title" className="tour-title">
        {step.title}
      </p>
      <p className="tour-body">{step.body}</p>
      <div className="tour-actions">
        <button type="button" className="text-button" onClick={close}>
          Skip
        </button>
        <span className="tour-spacer" />
        {index > 0 && (
          <button type="button" className="button button--secondary button--small" onClick={() => setIndex(index - 1)}>
            Back
          </button>
        )}
        <button type="button" className="button button--primary button--small" onClick={() => (last ? close() : setIndex(index + 1))}>
          {last ? "Got it" : "Next"}
        </button>
      </div>
      <div className="tour-dots" aria-hidden="true">
        {steps.map((s, i) => (
          <span key={s.target} className={i === index ? "is-on" : undefined} />
        ))}
      </div>
    </div>
  );
}
