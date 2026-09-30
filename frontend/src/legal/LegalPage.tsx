import { useEffect, useRef, useState } from "react";
import { errorMessage } from "../api";
import type { StreamHandlers } from "../api";
import { plural } from "../format";
import type { SourcesFound, StageId } from "../types";
import { analyzeCaseStream } from "./api";
import CaseReport from "./CaseReport";
import type { CaseIntelligence } from "./types";
import "./legal.css";

const MIN_CHARS = 40;
const MAX_CHARS = 10_000;

/** Hypothetical, for trying the tool without typing a real situation. */
const SAMPLE_CASE = `I worked as a warehouse lead for a logistics company in Fresno, California for about three years. \
On March 3 I emailed HR to complain that my supervisor was not paying overtime when we worked more than 8 hours \
a day. About two weeks later my schedule was cut from 40 to 24 hours a week. On March 20 my supervisor told me \
I was being let go because of "restructuring", but two newer employees on my team were kept. I have not received \
my final paycheck yet, and nobody has told me when it will come. I still have the email I sent to HR.`;

type State =
  | { status: "idle" }
  | { status: "loading"; stage?: StageId; topics?: string[]; sources?: SourcesFound }
  | { status: "error"; message: string; requestId?: string }
  | { status: "done"; result: CaseIntelligence };

const STEPS: { id: StageId; title: string; detail: string }[] = [
  { id: "EXTRACTING_CLAIMS", title: "Organising the description", detail: "Facts, dates, place, and what's missing." },
  { id: "SEARCHING", title: "Searching official sources", detail: "Statutes, regulations and agency guidance only." },
  { id: "ASSESSING", title: "Matching sources to topics", detail: "Checking which sources address each topic." },
];

export default function LegalPage() {
  const [description, setDescription] = useState("");
  const [state, setState] = useState<State>({ status: "idle" });
  const [problem, setProblem] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const alertRef = useRef<HTMLDivElement>(null);

  useEffect(() => () => controllerRef.current?.abort(), []);
  useEffect(() => {
    if (state.status === "error") alertRef.current?.focus();
  }, [state.status]);

  async function analyze() {
    const text = description.trim();
    if (text.length < MIN_CHARS) {
      setProblem("Describe the situation in a few sentences: what happened, when, and where.");
      return;
    }
    if (text.length > MAX_CHARS) {
      setProblem(`Please keep the description under ${MAX_CHARS.toLocaleString()} characters.`);
      return;
    }
    setProblem(null);
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setState({ status: "loading" });
    const update = (patch: Partial<Extract<State, { status: "loading" }>>) =>
      setState((s) => (s.status === "loading" && !controller.signal.aborted ? { ...s, ...patch } : s));
    const handlers: StreamHandlers = {
      onStage: (stage) => update({ stage }),
      onClaims: (topics) => update({ topics }),
      onSources: (sources) => update({ sources }),
    };
    try {
      const result = await analyzeCaseStream(text, handlers, controller.signal);
      if (controller.signal.aborted) return;
      setState({ status: "done", result });
      window.scrollTo(0, 0);
    } catch (err) {
      if (controller.signal.aborted) return;
      setState({ status: "error", ...errorMessage(err) });
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }

  function cancel() {
    controllerRef.current?.abort();
    controllerRef.current = null;
    setState({ status: "idle" });
  }

  if (state.status === "done") {
    return <CaseReport result={state.result} onNewCase={() => setState({ status: "idle" })} />;
  }

  const loading = state.status === "loading";
  const current = loading ? STEPS.findIndex((s) => s.id === (state.stage ?? "EXTRACTING_CLAIMS")) : -1;

  return (
    <div className="legal">
      <section className="intro" aria-labelledby="legal-heading">
        <p className="legal-brand">
          <span className="legal-brand-name">LegalFact</span> by VeriFact
        </p>
        <h1 id="legal-heading">Case intelligence from a plain-language description.</h1>
        <p className="lede">
          Paste how a person describes their legal situation. LegalFact organises it into facts, a timeline, the
          likely jurisdiction and what&apos;s missing, and points to official sources a professional may want to
          review.
        </p>
        <p className="legal-notice" role="note">
          <strong>For professional review. Not legal advice.</strong> LegalFact does not assess whether anyone has a
          claim.
        </p>
      </section>

      {state.status === "error" && (
        <div className="alert" role="alert" ref={alertRef} tabIndex={-1}>
          <p className="alert-title">We couldn&apos;t complete that analysis</p>
          <p>{state.message}</p>
          {state.requestId && <p className="alert-ref">Reference: {state.requestId}</p>}
        </div>
      )}

      {loading ? (
        <section className="card progress" aria-labelledby="legal-progress-heading" aria-busy="true">
          <div className="progress-head">
            <h2 id="legal-progress-heading">Analysing the description</h2>
          </div>
          <ol className="stages">
            {STEPS.map((step, index) => {
              const status = index < current ? "done" : index === current ? "current" : "upcoming";
              return (
                <li key={step.id} className={`stage stage--${status}`}>
                  <span className="stage-marker" aria-hidden="true" />
                  <div className="stage-body">
                    <span className="stage-title">
                      {step.title}
                      <span className="visually-hidden">{status === "done" ? " (done)" : status === "current" ? " (in progress)" : ""}</span>
                    </span>
                    <span className="stage-detail">{step.detail}</span>
                    {step.id === "EXTRACTING_CLAIMS" && state.topics && state.topics.length > 0 && (
                      <div className="stage-found">
                        <span className="stage-found-label">Topics to research</span>
                        <ul className="found-claims">
                          {state.topics.map((t) => (
                            <li key={t}>{t}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                    {step.id === "SEARCHING" && state.sources && (
                      <div className="stage-found">
                        <span className="stage-found-label">Found {plural(state.sources.count, "official source")}</span>
                        {state.sources.domains.length > 0 && (
                          <span className="found-domains">{state.sources.domains.join(" · ")}</span>
                        )}
                      </div>
                    )}
                  </div>
                </li>
              );
            })}
          </ol>
          <div className="progress-foot">
            <button type="button" className="button button--quiet button--small" onClick={cancel}>
              Cancel
            </button>
            <p className="progress-note">This usually takes 30–60 seconds.</p>
          </div>
        </section>
      ) : (
        <form
          className="card form-card legal-form"
          onSubmit={(e) => {
            e.preventDefault();
            void analyze();
          }}
          noValidate
        >
          <label htmlFor="case-description" className="legal-label">
            Case description
          </label>
          <textarea
            id="case-description"
            className="text-input legal-input"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What happened, when, where, and who was involved, in the person's own words."
            aria-describedby="case-hint"
            aria-invalid={problem ? true : undefined}
            rows={10}
          />
          <div className="input-foot">
            <p id="case-hint" className="hint">
              Use a hypothetical or anonymised description. Nothing is stored, but the text is sent to our AI and
              search providers. US situations only for now.
            </p>
            <span className={description.length > MAX_CHARS ? "counter counter--over" : "counter"}>
              {description.length.toLocaleString()} / {MAX_CHARS.toLocaleString()}
            </span>
          </div>
          {problem && (
            <p className="field-problem" role="alert">
              {problem}
            </p>
          )}
          <div className="form-actions">
            <button type="submit" className="button button--primary">
              Analyse description
            </button>
            <button type="button" className="text-button" onClick={() => setDescription(SAMPLE_CASE)}>
              Use a sample case (hypothetical)
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
