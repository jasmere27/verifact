import { useEffect, useState } from "react";
import { ApiError, getVerification } from "../api";
import type { VerificationResult } from "../types";
import Link from "./Link";
import Report from "./Report";

type State =
  | { status: "loading" }
  | { status: "ready"; result: VerificationResult }
  | { status: "missing" }
  | { status: "error"; message: string };

interface Props {
  id: string;
  /** Result already in memory (just checked, or opened earlier this session). */
  cached?: VerificationResult;
  onLoaded: (result: VerificationResult) => void;
}

export default function ReportPage({ id, cached, onLoaded }: Props) {
  const [state, setState] = useState<State>(cached ? { status: "ready", result: cached } : { status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (cached) return;
    const controller = new AbortController();
    getVerification(id, controller.signal)
      .then((result) => {
        setState({ status: "ready", result });
        onLoaded(result);
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        if (err instanceof ApiError && err.status === 404) setState({ status: "missing" });
        else setState({ status: "error", message: err instanceof Error ? err.message : "Couldn't load this report." });
      });
    return () => controller.abort();
  }, [id, attempt, cached, onLoaded]);

  if (state.status === "ready") return <Report result={state.result} />;

  if (state.status === "loading") {
    return (
      <div className="card state-card" role="status">
        <span className="spinner" aria-hidden="true" />
        <p>Loading report…</p>
      </div>
    );
  }

  if (state.status === "missing") {
    return (
      <section className="card state-card" aria-labelledby="missing-heading">
        <h1 id="missing-heading">Report not found</h1>
        <p className="muted">
          There&apos;s no report at this link. It may have been mistyped, or the report may no longer be stored.
        </p>
        <Link href="/" className="button button--primary">
          Check a claim
        </Link>
      </section>
    );
  }

  return (
    <section className="card state-card" aria-labelledby="error-heading">
      <h1 id="error-heading">Couldn&apos;t load this report</h1>
      <p role="alert">{state.message}</p>
      <div className="row">
        <button
          type="button"
          className="button button--primary"
          onClick={() => {
            setState({ status: "loading" });
            setAttempt((n) => n + 1);
          }}
        >
          Try again
        </button>
        <Link href="/" className="button button--secondary">
          Back to VeriFact
        </Link>
      </div>
    </section>
  );
}
