import Brand from "./components/BrandMark";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, errorMessage, verifyFileStream, verifyTextStream } from "./api";
import type { StreamHandlers } from "./api";
import CheckForm from "./components/CheckForm";
import type { Submission } from "./components/CheckForm";
import CheckProgress from "./components/CheckProgress";
import Link from "./components/Link";
import RecentChecks from "./components/RecentChecks";
import InstallPage from "./components/InstallPage";
import ShareSite from "./components/ShareSite";
import SiteNav from "./components/SiteNav";
import BottomNav from "./components/BottomNav";
import MobileHome from "./home/MobileHome";
import { useIsMobile, useTyping } from "./useMobile";
import ReportPage from "./components/ReportPage";
import ThemeToggle from "./components/ThemeToggle";
import AccountMenu from "./auth/AccountMenu";
import { accessToken, getMyChecks, refreshedAccessToken, removeMyCheck } from "./auth/api";
import type { MyCheck } from "./auth/api";
import { useAuth } from "./auth/useAuth";
import { AccountPage } from "./auth/AccountPage";
import { AuthCallbackPage, ForgotPasswordPage, ResetPasswordPage, SignInPage, SignUpPage } from "./auth/AuthPages";
import { authEnabled } from "./auth/client";
import "./auth/auth.css";
import Landing from "./landing/Landing";
import LegalPage from "./legal/LegalPage";
import ResearchPage from "./research/ResearchPage";
import StudentWorkspace from "./research/student/StudentWorkspace";
import ProjectPage from "./research/projects/ProjectPage";
import NewsPage from "./news/NewsPage";
import NewsWorkspace from "./news/NewsWorkspace";
import { CONTACT_EMAIL, PrivacyPage, TermsPage } from "./policies/PolicyPages";
import { formatRelative } from "./format";
import { clearRecent, forgetCheck, loadRecent, rememberCheck } from "./recent";
import type { RecentCheck } from "./recent";
import { newEditToken, saveReportToken } from "./reportTokens";
import { checkIntent } from "./shared";
import type { CheckIntent } from "./shared";
import { navigate, parseRoute, reportPath, usePathname } from "./router";
import type { Route } from "./router";
import type { OverallVerdict, SourcesFound, StageId, VerificationResult } from "./types";
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

const AUTH_TITLES: Partial<Record<Route["name"], string>> = {
  signIn: "Sign in · VeriFact",
  signUp: "Create your account · VeriFact",
  forgotPassword: "Reset your password · VeriFact",
  resetPassword: "Choose a new password · VeriFact",
  authCallback: "Signing in · VeriFact",
  account: "Your account · VeriFact",
};

const PAGE_TITLES: Partial<Record<Route["name"], string>> = {
  privacy: "Privacy Policy · VeriFact",
  install: "Get the VeriFact app",
  about: "How VeriFact works",
  researchProject: "Capstone project · ResearchFact",
  terms: "Terms of Use · VeriFact",
};

function App() {
  const pathname = usePathname();
  const route = useMemo<Route>(() => {
    const parsed = parseRoute(pathname);
    // Without accounts configured in this build, the account pages don't exist.
    return !authEnabled && parsed.name in AUTH_TITLES ? { name: "notFound" } : parsed;
  }, [pathname]);
  const [check, setCheck] = useState<CheckState>({ status: "idle" });
  const [results, setResults] = useState<Record<string, VerificationResult>>({});
  const [recent, setRecent] = useState<RecentCheck[]>(() => loadRecent());
  const [announcement, setAnnouncement] = useState("");
  const [reused, setReused] = useState<ReusedReport | null>(null);
  // Shared from another app (installed app's share menu): pre-fill the check box once, then tidy the URL.
  // How /check was opened (shared text, starting tab, "run now" from the Home search bar); read on each visit.
  const [intent, setIntent] = useState<CheckIntent | null>(() => (route.name === "check" ? checkIntent(window.location.search) : null));
  const ranIntent = useRef<string | null>(null);
  // Signed in: the account's history (null until loaded) replaces this browser's recent checks.
  const userId = useAuth().session?.user.id ?? null;
  // Phones get an app shell: compact header, bottom tab bar (with a More sheet for the rest), a dashboard at "/".
  const isMobile = useIsMobile();
  const typing = useTyping();
  const [myChecks, setMyChecks] = useState<MyCheck[] | null>(null);
  const [myChecksError, setMyChecksError] = useState<string | null>(null);
  const [lastUserId, setLastUserId] = useState(userId);
  if (userId !== lastUserId) {
    setLastUserId(userId);
    setMyChecks(null);
    setMyChecksError(null);
    // Signing out cleared this browser's list (AuthProvider); show what's left, i.e. nothing.
    if (!userId) setRecent(loadRecent());
  }
  const controllerRef = useRef<AbortController | null>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  const loadMyChecks = useCallback(() => {
    getMyChecks().then(
      (items) => {
        setMyChecks(items);
        setMyChecksError(null);
      },
      (e: unknown) => setMyChecksError(e instanceof Error ? e.message : "We couldn't load your checks."),
    );
  }, []);

  useEffect(() => {
    if (userId) loadMyChecks();
  }, [userId, loadMyChecks]);

  useEffect(() => {
    if (route.name === "check" && window.location.search) navigate("/check", { replace: true });
  }, [route.name]);

  // "Check" pressed on the Home search bar: start right away, once.
  useEffect(() => {
    if (route.name !== "check" || !intent?.run || !intent.text) return;
    if (ranIntent.current === intent.text) return;
    ranIntent.current = intent.text;
    void runCheck({ mode: "text", text: intent.text });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.name, intent]);

  const cacheResult = useCallback((result: VerificationResult) => {
    setResults((prev) => (prev[result.id] ? prev : { ...prev, [result.id]: result }));
  }, []);

  // Leaving the check page (e.g. Back) abandons an in-flight check and clears a stale error.
  const [lastRouteName, setLastRouteName] = useState(route.name);
  if (route.name !== lastRouteName) {
    setLastRouteName(route.name);
    if (route.name !== "check" && check.status !== "idle") setCheck({ status: "idle" });
    if (route.name === "check" && reused) setReused(null);
    if (route.name === "check") setIntent(checkIntent(window.location.search));
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
          : (AUTH_TITLES[route.name] ?? PAGE_TITLES[route.name] ?? "VeriFact · Check claims against the evidence");
  }, [route, results]);

  useEffect(() => {
    if (check.status === "error") errorRef.current?.focus();
  }, [check]);

  async function runCheck(submission: Submission, refresh = false) {
    const submittedAt = Date.now();
    const editToken = newEditToken();
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
    const run = (token: string | null) =>
      submission.mode === "text"
        ? verifyTextStream(submission.text, handlers, controller.signal, { refresh, editToken, accessToken: token })
        : verifyFileStream(submission.mode, submission.file, handlers, controller.signal, { editToken, accessToken: token });
    try {
      const token = userId ? await accessToken() : null;
      let result: VerificationResult;
      try {
        result = await run(token);
      } catch (err) {
        // An expired session: refresh once; if that fails, the check still runs signed out.
        if (!token || !(err instanceof ApiError) || err.status !== 401) throw err;
        result = await run(await refreshedAccessToken());
      }
      if (controller.signal.aborted) return;
      controllerRef.current = null;
      cacheResult(result);
      if (userId) loadMyChecks();
      else setRecent(rememberCheck(result));
      setCheck({ status: "idle" });
      const createdAt = Date.parse(result.createdAt);
      const wasReused = !Number.isNaN(createdAt) && createdAt < submittedAt - REUSE_THRESHOLD_MS;
      setReused(wasReused ? { id: result.id, submittedAt } : null);
      // The server only accepts the token on a report this check created, not on a reused one.
      if (!wasReused) saveReportToken(result.id, editToken);
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

  function reportDeleted(id: string) {
    setResults((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    setRecent(forgetCheck(id));
    setMyChecks((prev) => prev && prev.filter((c) => c.id !== id));
    navigate("/check", { replace: true });
    setAnnouncement("Report deleted.");
  }

  function removeFromHistory(id: string) {
    setMyChecks((prev) => prev && prev.filter((c) => c.id !== id));
    removeMyCheck(id).then(
      () => setAnnouncement("Removed from your checks."),
      (e: unknown) => {
        setAnnouncement(e instanceof Error ? e.message : "Couldn't remove that check.");
        loadMyChecks();
      },
    );
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
    <div
      className={["app", route.name === "report" ? "app--report" : "", isMobile ? "app--mobile" : "", isMobile && typing ? "app--typing" : ""]
        .filter(Boolean)
        .join(" ")}
    >
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="site-header">
        <div className="site-header-inner">
          <Link href="/" className="wordmark" aria-label="VeriFact home">
            <Brand />
          </Link>
          <div className="header-end">
            {!isMobile && <SiteNav route={route.name} />}
            {/* Signed in, the theme switch is in the account menu instead. */}
            {!isMobile && !userId && <ThemeToggle />}
            <AccountMenu />
          </div>
        </div>
      </header>

      <main
        id="main"
        className={(route.name === "landing" && !isMobile) || route.name === "about" ? "main main--wide" : route.name === "research" ? "main main--research" : "main"}
      >
        {route.name === "landing" && (isMobile ? <MobileHome /> : <Landing />)}
        {route.name === "about" && <Landing />}

        {route.name === "check" && (
          <>
            <section className="intro" aria-labelledby="page-heading">
              <h1 id="page-heading">Saw something viral? <span className="headline-accent">Check it first.</span></h1>
              <p className="lede">
                Paste a post, a link or a screenshot and see what fact-checkers, news and reference sources say.
              </p>
              {!isMobile && (
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
              )}
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
              <CheckForm
                initialText={intent?.text ?? undefined}
                initialMode={intent?.mode}
                onSubmit={(submission) => {
                  setIntent(null);
                  void runCheck(submission);
                }}
              />
            </div>

            {!loading &&
              (userId ? (
                <RecentChecks
                  mode="account"
                  items={
                    myChecks &&
                    myChecks.map((c) => ({
                      id: c.id,
                      overallVerdict: c.overallVerdict as OverallVerdict,
                      label: c.label,
                      createdAt: c.checkedAt,
                    }))
                  }
                  error={myChecksError}
                  onRemove={removeFromHistory}
                />
              ) : (
                <RecentChecks
                  mode="browser"
                  items={recent}
                  onClear={() => {
                    clearRecent();
                    setRecent([]);
                    setAnnouncement("Recent checks cleared.");
                  }}
                />
              ))}
          </>
        )}

        {route.name === "report" && (
          <ReportPage
            key={route.id}
            id={route.id}
            cached={results[route.id]}
            onLoaded={cacheResult}
            onDeleted={reportDeleted}
            yours={Boolean(myChecks?.find((c) => c.id === route.id)?.yours)}
            reusedAt={reused?.id === route.id ? reused.submittedAt : undefined}
            onRecheck={recheck}
          />
        )}

        {route.name === "legal" && <LegalPage />}

        {route.name === "research" && <ResearchPage />}

        {route.name === "researchWorkspace" && <StudentWorkspace key={route.id} id={route.id} />}

        {route.name === "researchProject" && <ProjectPage key={route.id} id={route.id} />}

        {route.name === "news" && <NewsPage />}

        {route.name === "newsWorkspace" && <NewsWorkspace key={route.id} id={route.id} />}

        {route.name === "signIn" && <SignInPage />}
        {route.name === "signUp" && <SignUpPage />}
        {route.name === "forgotPassword" && <ForgotPasswordPage />}
        {route.name === "resetPassword" && <ResetPasswordPage />}
        {route.name === "authCallback" && <AuthCallbackPage />}
        {route.name === "account" && <AccountPage />}

        {route.name === "install" && <InstallPage />}
        {route.name === "privacy" && <PrivacyPage />}
        {route.name === "terms" && <TermsPage />}

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

      {/* On phones the footer's links live in the tab bar's More sheet. */}
      {!isMobile && (
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
              <Link href="/install">Get the app</Link>
              <ShareSite className="site-footer-share" />
            </div>
            <div>
              <p className="site-footer-heading">Legal</p>
              <Link href="/privacy">Privacy Policy</Link>
              <Link href="/terms">Terms of Use</Link>
              <a href={`mailto:${CONTACT_EMAIL}`}>Contact</a>
            </div>
          </nav>
        </div>
        <p className="site-footer-legal">
          LegalFact provides legal information, not legal advice. Results can be wrong: check the linked sources before
          acting on them.
        </p>
      </footer>
      )}

      {isMobile && <BottomNav route={route.name} />}

      <div className="visually-hidden" aria-live="polite" aria-atomic="true">
        {announcement}
      </div>
    </div>
  );
}

export default App;
