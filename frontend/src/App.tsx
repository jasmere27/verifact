import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, verifyAudio, verifyImage, verifyText } from "./api";
import CheckForm from "./components/CheckForm";
import type { Submission } from "./components/CheckForm";
import CheckProgress from "./components/CheckProgress";
import Link from "./components/Link";
import RecentChecks from "./components/RecentChecks";
import ReportPage from "./components/ReportPage";
import { clearRecent, loadRecent, rememberCheck } from "./recent";
import type { RecentCheck } from "./recent";
import { navigate, parseRoute, reportPath, usePathname } from "./router";
import type { VerificationResult } from "./types";
import { verdictMeta } from "./verdicts";
import "./App.css";

type CheckState =
  | { status: "idle" }
  | { status: "loading"; submission: Submission }
  | { status: "error"; message: string; requestId?: string };

function errorMessage(err: unknown): { message: string; requestId?: string } {
  if (err instanceof ApiError) {
    const wait =
      err.status === 429 && err.retryAfterSeconds ? ` You can try again in about ${err.retryAfterSeconds} seconds.` : "";
    return { message: `${err.message}${wait}`, requestId: err.requestId };
  }
  return { message: "Something went wrong. Please try again." };
}

function App() {
  const route = parseRoute(usePathname());
  const [check, setCheck] = useState<CheckState>({ status: "idle" });
  const [results, setResults] = useState<Record<string, VerificationResult>>({});
  const [recent, setRecent] = useState<RecentCheck[]>(() => loadRecent());
  const [announcement, setAnnouncement] = useState("");
  const controllerRef = useRef<AbortController | null>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  const cacheResult = useCallback((result: VerificationResult) => {
    setResults((prev) => (prev[result.id] ? prev : { ...prev, [result.id]: result }));
  }, []);

  // Leaving the check page (e.g. Back) abandons an in-flight check and clears a stale error.
  const [lastRouteName, setLastRouteName] = useState(route.name);
  if (route.name !== lastRouteName) {
    setLastRouteName(route.name);
    if (route.name !== "home" && check.status !== "idle") setCheck({ status: "idle" });
  }
  useEffect(() => {
    if (route.name === "home") return;
    controllerRef.current?.abort();
    controllerRef.current = null;
  }, [route.name]);

  useEffect(() => {
    document.title =
      route.name === "report"
        ? results[route.id]
          ? `${verdictMeta(results[route.id].overallVerdict).label} · VeriFact report`
          : "Report · VeriFact"
        : route.name === "notFound"
          ? "Page not found · VeriFact"
          : "VeriFact · Check claims against the evidence";
  }, [route, results]);

  useEffect(() => {
    if (check.status === "error") errorRef.current?.focus();
  }, [check]);

  async function runCheck(submission: Submission) {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setCheck({ status: "loading", submission });
    setAnnouncement("Checking the evidence. This usually takes 10 to 40 seconds.");
    try {
      const result =
        submission.mode === "text"
          ? await verifyText(submission.text, controller.signal)
          : submission.mode === "image"
            ? await verifyImage(submission.file, controller.signal)
            : await verifyAudio(submission.file, controller.signal);
      if (controller.signal.aborted) return;
      controllerRef.current = null;
      cacheResult(result);
      setRecent(rememberCheck(result));
      setCheck({ status: "idle" });
      setAnnouncement(`Report ready. Overall: ${verdictMeta(result.overallVerdict).label}.`);
      navigate(reportPath(result.id));
    } catch (err) {
      if (controller.signal.aborted) return;
      controllerRef.current = null;
      const { message, requestId } = errorMessage(err);
      setCheck({ status: "error", message, requestId });
      setAnnouncement("");
    }
  }

  function cancelCheck() {
    controllerRef.current?.abort();
    controllerRef.current = null;
    setCheck({ status: "idle" });
    setAnnouncement("Check cancelled.");
  }

  const loading = check.status === "loading";

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="site-header">
        <div className="site-header-inner">
          <Link href="/" className="wordmark" aria-label="VeriFact home">
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" focusable="false">
              <rect x="1" y="1" width="22" height="22" rx="5" fill="var(--accent)" />
              <path d="M6.5 12.3l3.4 3.4 7.6-7.9" fill="none" stroke="var(--on-accent)" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span>VeriFact</span>
          </Link>
          <p className="slogan">Verify Truth, Fight the False</p>
        </div>
      </header>

      <main id="main" className="main">
        {route.name === "home" && (
          <>
            <section className="intro" aria-labelledby="page-heading">
              <h1 id="page-heading">Check a claim against the evidence</h1>
              <p className="lede">
                Paste a claim, an article or a link — or upload a screenshot or a short voice clip. VeriFact picks out
                the specific factual claims, searches the web for reporting on each, and shows you which sources support
                or contradict them, and what remains uncertain.
              </p>
            </section>

            {check.status === "error" && (
              <div className="alert" role="alert" ref={errorRef} tabIndex={-1}>
                <p className="alert-title">We couldn&apos;t complete that check</p>
                <p>{check.message}</p>
                {check.requestId && <p className="alert-ref">Reference: {check.requestId}</p>}
              </div>
            )}

            {loading && <CheckProgress submission={check.submission} onCancel={cancelCheck} />}

            <div className="card form-card" hidden={loading}>
              <CheckForm onSubmit={runCheck} />
            </div>

            {!loading && (
              <RecentChecks
                items={recent}
                onClear={() => {
                  clearRecent();
                  setRecent([]);
                  setAnnouncement("Recent checks cleared.");
                }}
              />
            )}
          </>
        )}

        {route.name === "report" && (
          <ReportPage key={route.id} id={route.id} cached={results[route.id]} onLoaded={cacheResult} />
        )}

        {route.name === "notFound" && (
          <section className="card state-card" aria-labelledby="nf-heading">
            <h1 id="nf-heading">Page not found</h1>
            <p className="muted">There&apos;s nothing at this address.</p>
            <Link href="/" className="button button--primary">
              Go to VeriFact
            </Link>
          </section>
        )}
      </main>

      <footer className="site-footer">
        <p>
          <strong>VeriFact</strong> — Verify Truth, Fight the False.
        </p>
        <p>An aid for checking claims against published sources, not a final authority.</p>
      </footer>

      <div className="visually-hidden" aria-live="polite" aria-atomic="true">
        {announcement}
      </div>
    </div>
  );
}

export default App;
