import Brand from "./components/BrandMark";
import { useCallback, useEffect, useRef, useState } from "react";
import { errorMessage, verifyFileStream, verifyTextStream } from "./api";
import type { StreamHandlers } from "./api";
import CheckForm from "./components/CheckForm";
import type { Submission } from "./components/CheckForm";
import CheckProgress from "./components/CheckProgress";
import Link from "./components/Link";
import RecentChecks from "./components/RecentChecks";
import ReportPage from "./components/ReportPage";
import ThemeToggle from "./components/ThemeToggle";
import Landing from "./landing/Landing";
import LegalPage from "./legal/LegalPage";
import ResearchPage from "./research/ResearchPage";
import StudentWorkspace from "./research/student/StudentWorkspace";
import NewsPage from "./news/NewsPage";
import NewsWorkspace from "./news/NewsWorkspace";
import { formatRelative } from "./format";
import { clearRecent, loadRecent, rememberCheck } from "./recent";
import type { RecentCheck } from "./recent";
import { navigate, parseRoute, reportPath, usePathname } from "./router";
import type { SourcesFound, StageId, VerificationResult } from "./types";
import { verdictMeta } from "./verdicts";
import "./App.css";

type CheckState =
  | { status: "idle" }
  | { status: "loading"; submission: Submission; stage?: StageId; claims?: string[]; sources?: SourcesFound }
  | { status: "error"; message: string; requestId?: string };

/** A result created this long before the user submitted was reused by the server, not freshly checked. */
const REUSE_THRESHOLD_MS = 2 * 60_000;

/** Set when a fresh submission landed on a stored report; lives only in memory, never in the URL. */
interface ReusedReport {
  id: string;
  submittedAt: number;
}

function App() {
  const route = parseRoute(usePathname());
  const [check, setCheck] = useState<CheckState>({ status: "idle" });
  const [results, setResults] = useState<Record<string, VerificationResult>>({});
  const [recent, setRecent] = useState<RecentCheck[]>(() => loadRecent());
  const [announcement, setAnnouncement] = useState("");
  const [reused, setReused] = useState<ReusedReport | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  const cacheResult = useCallback((result: VerificationResult) => {
    setResults((prev) => (prev[result.id] ? prev : { ...prev, [result.id]: result }));
  }, []);

  // Leaving the check page (e.g. Back) abandons an in-flight check and clears a stale error.
  const [lastRouteName, setLastRouteName] = useState(route.name);
  if (route.name !== lastRouteName) {
    setLastRouteName(route.name);
    if (route.name !== "check" && check.status !== "idle") setCheck({ status: "idle" });
    if (route.name === "check" && reused) setReused(null);
  }
  useEffect(() => {
    if (route.name === "check") return;
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

  async function runCheck(submission: Submission, refresh = false) {
    const submittedAt = Date.now();
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setCheck({ status: "loading", submission });
    setAnnouncement("Checking the evidence. This usually takes 10 to 40 seconds.");
    // Live updates from the stream; ignored once this check is cancelled or superseded.
    const update = (patch: { stage?: StageId; claims?: string[]; sources?: SourcesFound }) => {
      if (controller.signal.aborted) return;
      setCheck((prev) => (prev.status === "loading" ? { ...prev, ...patch } : prev));
    };
    const handlers: StreamHandlers = {
      onStage: (stage) => update({ stage }),
      onClaims: (claims) => update({ claims }),
      onSources: (sources) => update({ sources }),
    };
    try {
      const result =
        submission.mode === "text"
          ? await verifyTextStream(submission.text, handlers, controller.signal, refresh)
          : await verifyFileStream(submission.mode, submission.file, handlers, controller.signal);
      if (controller.signal.aborted) return;
      controllerRef.current = null;
      cacheResult(result);
      setRecent(rememberCheck(result));
      setCheck({ status: "idle" });
      const createdAt = Date.parse(result.createdAt);
      const wasReused = !Number.isNaN(createdAt) && createdAt < submittedAt - REUSE_THRESHOLD_MS;
      setReused(wasReused ? { id: result.id, submittedAt } : null);
      const label = verdictMeta(result.overallVerdict).label;
      setAnnouncement(
        wasReused
          ? `This was already checked ${formatRelative(result.createdAt, submittedAt)}. Showing that report. Overall: ${label}.`
          : `Report ready. Overall: ${label}.`,
      );
      navigate(reportPath(result.id));
    } catch (err) {
      if (controller.signal.aborted) return;
      controllerRef.current = null;
      const { message, requestId } = errorMessage(err);
      setCheck({ status: "error", message, requestId });
      setAnnouncement("");
    }
  }

  /** "Check again now" on a reused report: run the same text or link again, bypassing reuse. */
  function recheck(input: string) {
    navigate("/check");
    void runCheck({ mode: "text", text: input }, true);
  }

  function cancelCheck() {
    controllerRef.current?.abort();
    controllerRef.current = null;
    setCheck({ status: "idle" });
    setAnnouncement("Check cancelled.");
  }

  const loading = check.status === "loading";

  return (
    <div className={route.name === "report" ? "app app--report" : "app"}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="site-header">
        <div className="site-header-inner">
          <Link href="/" className="wordmark" aria-label="VeriFact home">
            <Brand />
          </Link>
          <div className="header-end">
            <nav className="site-nav" aria-label="Products">
              <Link href="/check" aria-current={route.name === "check" || route.name === "report" ? "page" : undefined}>
                VeriFact
              </Link>
              <Link href="/news" aria-current={route.name === "news" || route.name === "newsWorkspace" ? "page" : undefined}>
                NewsFact
              </Link>
              <Link href="/legal" aria-current={route.name === "legal" ? "page" : undefined}>
                LegalFact
              </Link>
              <Link href="/research" aria-current={route.name === "research" || route.name === "researchWorkspace" ? "page" : undefined}>
                ResearchFact
              </Link>
            </nav>
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main id="main" className={route.name === "landing" ? "main main--wide" : "main"}>
        {route.name === "landing" && <Landing />}

        {route.name === "check" && (
          <>
            <section className="intro" aria-labelledby="page-heading">
              <h1 id="page-heading">Saw something viral? <span className="headline-accent">Check it first.</span></h1>
              <p className="lede">
                Paste a post, a link or a screenshot and see what fact-checkers, news and reference sources say.
              </p>
              <ol className="how-steps" aria-label="How it works">
                <li>
                  <span className="how-num" aria-hidden="true">1</span>
                  <span>Paste a post or link</span>
                </li>
                <li>
                  <span className="how-num" aria-hidden="true">2</span>
                  <span>We search the web</span>
                </li>
                <li>
                  <span className="how-num" aria-hidden="true">3</span>
                  <span>See the evidence</span>
                </li>
              </ol>
            </section>

            {check.status === "error" && (
              <div className="alert" role="alert" ref={errorRef} tabIndex={-1}>
                <p className="alert-title">We couldn&apos;t complete that check</p>
                <p>{check.message}</p>
                {check.requestId && <p className="alert-ref">Reference: {check.requestId}</p>}
              </div>
            )}

            {loading && (
              <CheckProgress
                submission={check.submission}
                stage={check.stage}
                claims={check.claims}
                sources={check.sources}
                onCancel={cancelCheck}
              />
            )}

            <div className="card form-card" hidden={loading}>
              <CheckForm onSubmit={(submission) => void runCheck(submission)} />
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
          <ReportPage
            key={route.id}
            id={route.id}
            cached={results[route.id]}
            onLoaded={cacheResult}
            reusedAt={reused?.id === route.id ? reused.submittedAt : undefined}
            onRecheck={recheck}
          />
        )}

        {route.name === "legal" && <LegalPage />}

        {route.name === "research" && <ResearchPage />}

        {route.name === "researchWorkspace" && <StudentWorkspace key={route.id} id={route.id} />}

        {route.name === "news" && <NewsPage />}

        {route.name === "newsWorkspace" && <NewsWorkspace key={route.id} id={route.id} />}

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
        <div className="site-footer-inner">
          <div className="site-footer-brand">
            <Link href="/" className="wordmark wordmark--footer" aria-label="VeriFact home">
              <Brand size={26} />
            </Link>
            <p>Evidence intelligence.</p>
            <p>An aid for checking claims against published sources, not a final authority.</p>
          </div>
          <nav className="site-footer-links" aria-label="Footer">
            <div>
              <p className="site-footer-heading">Products</p>
              <Link href="/check">VeriFact</Link>
              <Link href="/news">NewsFact</Link>
              <Link href="/legal">LegalFact</Link>
              <Link href="/research">ResearchFact</Link>
            </div>
            <div>
              <p className="site-footer-heading">Learn</p>
              <a href="/#how">How it works</a>
              <a href="/#trust">Evidence &amp; trust</a>
              <a href="/#pricing">Pricing</a>
              <a href="/#faq">FAQ</a>
            </div>
          </nav>
        </div>
        <p className="site-footer-legal">
          LegalFact provides legal information, not legal advice. Results can be wrong: check the linked sources before
          acting on them.
        </p>
      </footer>

      <div className="visually-hidden" aria-live="polite" aria-atomic="true">
        {announcement}
      </div>
    </div>
  );
}

export default App;
