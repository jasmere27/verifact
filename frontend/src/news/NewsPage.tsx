import { useEffect, useRef, useState } from "react";
import { errorMessage } from "../api";
import type { StreamHandlers } from "../api";
import { plural } from "../format";
import { navigate } from "../router";
import { useIsMobile } from "../useMobile";
import type { SourcesFound, StageId } from "../types";
import { runNewsCheck } from "./api";
import "./news.css";

const MIN_CHARS = 60;
const MAX_CHARS = 10_000;

type State =
  | { status: "idle" }
  | { status: "loading"; stage?: StageId; claims?: string[]; sources?: SourcesFound }
  | { status: "error"; message: string; requestId?: string };

const STEPS: { id: StageId; title: string; detail: string }[] = [
  { id: "READING_INPUT", title: "Reading the article", detail: "Fetching the page safely (links only)." },
  { id: "EXTRACTING_CLAIMS", title: "Listing claims", detail: "Facts, statistics, quotes, dates and attributions." },
  { id: "SEARCHING", title: "Finding sources", detail: "Searching the web; the article's own site is excluded." },
  { id: "ASSESSING", title: "Verifying", detail: "Verdicts from the sources' own words, quotes checked word for word." },
];

/** NewsFact start page: paste a link or text, watch progress, land in the saved workspace. */
const Points = () => (
  <ul className="news-points" aria-label="What it checks">
    <li>Facts, statistics, dates and attributions</li>
    <li>Quotes, word for word</li>
    <li>Outdated figures and old events presented as new</li>
    <li>Sources that disagree</li>
  </ul>
);

export default function NewsPage() {
  const isMobile = useIsMobile();
  const [input, setInput] = useState("");
  const [state, setState] = useState<State>({ status: "idle" });
  const [problem, setProblem] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const alertRef = useRef<HTMLDivElement>(null);

  useEffect(() => () => controllerRef.current?.abort(), []);
  useEffect(() => {
    if (state.status === "error") alertRef.current?.focus();
  }, [state.status]);

  const isLink = /^https?:\/\/\S+$/i.test(input.trim());

  async function start() {
    const value = input.trim();
    if (!isLink && value.length < MIN_CHARS) {
      setProblem("Paste the article's link, or the full article text.");
      return;
    }
    if (value.length > MAX_CHARS) {
      setProblem(`That's too long; paste the link instead, or keep the text under ${MAX_CHARS.toLocaleString()} characters.`);
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
      const w = await runNewsCheck(value, handlers, controller.signal);
      if (controller.signal.aborted) return;
      navigate(`/news/${w.check.id}`);
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

  const loading = state.status === "loading";
  const steps = isLink ? STEPS : STEPS.slice(1);
  const current = loading ? steps.findIndex((s) => s.id === (state.stage ?? steps[0].id)) : -1;

  return (
    <div className="news">
      <section className="intro" aria-labelledby="news-heading">
        <p className="news-brand">
          <span className="news-brand-name">NewsFact</span> by VeriFact
        </p>
        <h1 id="news-heading">Fact-check a story before it runs.</h1>
        {isMobile ? (
          // Phones: the form first; the details one tap away.
          <>
            <p className="lede">Paste an article or its link. Every claim is checked against sources, quotes word for word.</p>
            <details className="news-more">
              <summary>What it checks</summary>
              <Points />
            </details>
          </>
        ) : (
          <>
            <p className="lede">
              Paste an article or its link. NewsFact lists every claim, checks each against sources in their own words,
              verifies quotes word for word, flags outdated or conflicting information, and gives your desk a review
              workspace and a report.
            </p>
            <Points />
          </>
        )}
      </section>

      {state.status === "error" && (
        <div className="alert" role="alert" ref={alertRef} tabIndex={-1}>
          <p className="alert-title">We couldn&apos;t complete that check</p>
          <p>{state.message}</p>
          {state.requestId && <p className="alert-ref">Reference: {state.requestId}</p>}
        </div>
      )}

      {loading ? (
        <section className="card progress" aria-labelledby="news-progress-heading" aria-busy="true">
          <div className="progress-head">
            <h2 id="news-progress-heading">Checking the story</h2>
          </div>
          <ol className="stages">
            {steps.map((step, index) => {
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
                        <span className="stage-found-label">Found {plural(state.claims.length, "claim")} to verify</span>
                      </div>
                    )}
                    {step.id === "SEARCHING" && state.sources && (
                      <div className="stage-found">
                        <span className="stage-found-label">Found {plural(state.sources.count, "source")}</span>
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
            <p className="progress-note">This usually takes 45–90 seconds.</p>
          </div>
        </section>
      ) : (
        <form
          className="card form-card news-form"
          onSubmit={(e) => {
            e.preventDefault();
            void start();
          }}
          noValidate
        >
          <label htmlFor="news-input" className="news-label">
            Article link or text
          </label>
          <textarea
            id="news-input"
            className="text-input news-input"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="https://… or paste the full article"
            aria-describedby="news-hint"
            aria-invalid={problem ? true : undefined}
            rows={10}
          />
          <div className="input-foot">
            <p id="news-hint" className="hint">
              Up to 10 claims per story. The check is saved as a workspace you can share by link; only you (this
              browser) can change its review.
            </p>
            <span className={input.length > MAX_CHARS ? "counter counter--over" : "counter"}>
              {input.length.toLocaleString()} / {MAX_CHARS.toLocaleString()}
            </span>
          </div>
          {problem && (
            <p className="field-problem" role="alert">
              {problem}
            </p>
          )}
          <div className="form-actions">
            <button type="submit" className="button button--primary">
              {isLink ? "Check this article" : "Check this text"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
