import { useEffect, useRef, useState } from "react";
import { errorMessage } from "../api";
import type { StreamHandlers } from "../api";
import { plural } from "../format";
import type { SourcesFound, StageId } from "../types";
import { checkResearchStream } from "./api";
import ResearchReport from "./ResearchReport";
import type { ResearchCheck } from "./types";
import "./research.css";

const MIN_CHARS = 30;
const MAX_CHARS = 10_000;

/** A mix with known answers: real, retracted, fabricated, and an overstated claim. */
const SAMPLE_TEXT = `The Transformer architecture relies solely on attention mechanisms, dispensing with recurrence and convolutions entirely (Vaswani et al., 2017). Deep learning allows computational models that are composed of multiple processing layers to learn representations of data with multiple levels of abstraction (LeCun et al., 2015). Deep learning has already solved general artificial intelligence (LeCun et al., 2015). An early study linked the MMR vaccine to autism (Wakefield et al., 1998). Drinking coffee doubles human lifespan (Johnson, 2022).

References
LeCun, Y., Bengio, Y., & Hinton, G. (2015). Deep learning. Nature, 521(7553), 436-444.
Johnson, R. (2022). Quantum coffee effects on human longevity: a randomized trial. Journal of Imaginary Nutrition, 4(2), 12-19.
Vaswani, A., Shazeer, N., Parmar, N., et al. (2017). Attention is all you need. https://doi.org/10.48550/arXiv.1706.03762
Wakefield, A. J., Murch, S. H., Anthony, A., et al. (1998). Ileal-lymphoid-nodular hyperplasia, non-specific colitis, and pervasive developmental disorder in children. The Lancet, 351(9103), 637-641. https://doi.org/10.1016/S0140-6736(97)11096-0`;

type State =
  | { status: "idle" }
  | { status: "loading"; stage?: StageId; claims?: string[]; sources?: SourcesFound }
  | { status: "error"; message: string; requestId?: string }
  | { status: "done"; result: ResearchCheck };

const STEPS: { id: StageId; title: string; detail: string }[] = [
  { id: "EXTRACTING_CLAIMS", title: "Finding citations", detail: "References and the claims that cite them." },
  { id: "SEARCHING", title: "Checking the records", detail: "Crossref, DataCite, OpenAlex and PubMed: existence, details, retractions." },
  { id: "ASSESSING", title: "Comparing claims with abstracts", detail: "Does the cited paper say this? Does other research disagree?" },
];

export default function ResearchPage() {
  const [text, setText] = useState("");
  const [state, setState] = useState<State>({ status: "idle" });
  const [problem, setProblem] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const alertRef = useRef<HTMLDivElement>(null);

  useEffect(() => () => controllerRef.current?.abort(), []);
  useEffect(() => {
    if (state.status === "error") alertRef.current?.focus();
  }, [state.status]);

  async function check() {
    const value = text.trim();
    if (value.length < MIN_CHARS) {
      setProblem("Paste text that cites research: a paragraph with in-text citations and its reference list, or DOIs.");
      return;
    }
    if (value.length > MAX_CHARS) {
      setProblem(`Please keep the text under ${MAX_CHARS.toLocaleString()} characters.`);
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
      onClaims: (claims) => update({ claims }),
      onSources: (sources) => update({ sources }),
    };
    try {
      const result = await checkResearchStream(value, handlers, controller.signal);
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
    return <ResearchReport result={state.result} onNewCheck={() => setState({ status: "idle" })} />;
  }

  const loading = state.status === "loading";
  const current = loading ? STEPS.findIndex((s) => s.id === (state.stage ?? "EXTRACTING_CLAIMS")) : -1;

  return (
    <div className="research">
      <section className="intro" aria-labelledby="research-heading">
        <p className="research-brand">
          <span className="research-brand-name">ResearchFact</span> by VeriFact
        </p>
        <h1 id="research-heading">Check every citation before it's published.</h1>
        <p className="lede">
          Paste a manuscript section, literature review or essay with its references. ResearchFact checks that each
          cited work exists and matches, flags retractions, and compares each claim with what the cited paper&apos;s
          abstract actually says, with the quote.
        </p>
        <ul className="research-points" aria-label="What it checks">
          <li>Fabricated or mismatched references</li>
          <li>Retracted and corrected papers</li>
          <li>Claims the cited paper doesn&apos;t support, or overstates</li>
          <li>Other research that reports a different finding</li>
        </ul>
      </section>

      {state.status === "error" && (
        <div className="alert" role="alert" ref={alertRef} tabIndex={-1}>
          <p className="alert-title">We couldn&apos;t complete that check</p>
          <p>{state.message}</p>
          {state.requestId && <p className="alert-ref">Reference: {state.requestId}</p>}
        </div>
      )}

      {loading ? (
        <section className="card progress" aria-labelledby="research-progress-heading" aria-busy="true">
          <div className="progress-head">
            <h2 id="research-progress-heading">Checking citations</h2>
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
                      <span className="visually-hidden">
                        {status === "done" ? " (done)" : status === "current" ? " (in progress)" : ""}
                      </span>
                    </span>
                    <span className="stage-detail">{step.detail}</span>
                    {step.id === "EXTRACTING_CLAIMS" && state.claims && state.claims.length > 0 && (
                      <div className="stage-found">
                        <span className="stage-found-label">Found {plural(state.claims.length, "cited claim")}</span>
                      </div>
                    )}
                    {step.id === "SEARCHING" && state.sources && (
                      <div className="stage-found">
                        <span className="stage-found-label">Found {plural(state.sources.count, "cited work")}</span>
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
            <p className="progress-note">This usually takes 30–90 seconds.</p>
          </div>
        </section>
      ) : (
        <form
          className="card form-card research-form"
          onSubmit={(e) => {
            e.preventDefault();
            void check();
          }}
          noValidate
        >
          <label htmlFor="research-text" className="research-label">
            Text with citations
          </label>
          <textarea
            id="research-text"
            className="text-input research-input"
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Paste a paragraph with in-text citations (Smith et al., 2020) and its reference list, or DOIs."
            aria-describedby="research-hint"
            aria-invalid={problem ? true : undefined}
            rows={12}
          />
          <div className="input-foot">
            <p id="research-hint" className="hint">
              Up to 12 references and 8 cited claims per check. Nothing is stored; the text is sent to our AI provider,
              and reference details to Crossref, DataCite, OpenAlex and PubMed.
            </p>
            <span className={text.length > MAX_CHARS ? "counter counter--over" : "counter"}>
              {text.length.toLocaleString()} / {MAX_CHARS.toLocaleString()}
            </span>
          </div>
          {problem && (
            <p className="field-problem" role="alert">
              {problem}
            </p>
          )}
          <div className="form-actions">
            <button type="submit" className="button button--primary">
              Check citations
            </button>
            <button type="button" className="text-button" onClick={() => setText(SAMPLE_TEXT)}>
              Use a sample (includes a retracted and a made-up reference)
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
