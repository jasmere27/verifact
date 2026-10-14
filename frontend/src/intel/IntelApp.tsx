import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { useAuth } from "../auth/useAuth";
import { navigate, usePathname } from "../router";
import { createWorkspace, orgApi } from "./api";
import { IntelContext, roleAtLeast } from "./context";
import type { IntelState } from "./context";
import { Icons } from "./icons";
import Link from "../components/Link";
import { message, useLoad } from "./lib";
import { Notice, Skeleton } from "./ui";
import BillingPage from "./pages/BillingPage";
import EvidencePage from "./pages/EvidencePage";
import IncidentPage from "./pages/IncidentPage";
import IncidentsPage from "./pages/IncidentsPage";
import InvestigationPage from "./pages/InvestigationPage";
import InvestigationsPage from "./pages/InvestigationsPage";
import MonitoringPage from "./pages/MonitoringPage";
import OverviewPage from "./pages/OverviewPage";
import ProjectPage from "./pages/ProjectPage";
import ReportPage from "./pages/ReportPage";
import ReportsPage from "./pages/ReportsPage";
import ResponsePage from "./pages/ResponsePage";
import ResponsesPage from "./pages/ResponsesPage";
import SettingsPage from "./pages/SettingsPage";
import "./intel.css";

const LAST_ORG = "verifact.intel.org";

const SECTIONS = [
  { id: "overview", label: "Overview", icon: Icons.overview },
  { id: "monitoring", label: "Monitoring", icon: Icons.monitoring },
  { id: "incidents", label: "Incidents", icon: Icons.incidents },
  { id: "investigations", label: "Investigations", icon: Icons.investigations },
  { id: "evidence", label: "Evidence Library", icon: Icons.evidence },
  { id: "responses", label: "Response Studio", icon: Icons.responses },
  { id: "reports", label: "Reports", icon: Icons.reports },
] as const;

const ADMIN_SECTIONS = [
  { id: "settings", label: "Settings & integrations", icon: Icons.settings },
  { id: "billing", label: "Subscription & usage", icon: Icons.billing },
] as const;

const TITLES: Record<string, string> = {
  overview: "Overview",
  monitoring: "Monitoring",
  incidents: "Incidents",
  investigations: "Investigations",
  evidence: "Evidence Library",
  responses: "Response Studio",
  reports: "Reports",
  settings: "Settings",
  billing: "Subscription & usage",
};

function readLastOrg(): string | null {
  try {
    return localStorage.getItem(LAST_ORG);
  } catch {
    return null;
  }
}

function rememberOrg(id: string) {
  try {
    localStorage.setItem(LAST_ORG, id);
  } catch {
    // private mode: the workspace just isn't remembered
  }
}

/** VeriFact Intelligence: the B2B workspace under /app. Signed-in only; everything is scoped to one workspace. */
export default function IntelApp() {
  const { enabled, ready, session, account, accountError, reloadAccount } = useAuth();
  const pathname = usePathname();
  const parts = pathname.replace(/^\/app\/?/, "").split("/").filter(Boolean);

  if (!enabled) {
    return (
      <Gate title="VeriFact Intelligence isn't available here">
        <p className="muted">Accounts aren't configured in this build, and the workspace needs a signed-in account.</p>
      </Gate>
    );
  }
  if (!ready || (session && !account && !accountError)) {
    return (
      <div className="intel">
        <Skeleton lines={6} />
      </div>
    );
  }
  if (!session) {
    return (
      <Gate title="Sign in to VeriFact Intelligence">
        <p className="muted">Brand monitoring, evidence investigations and incident response for communications teams.</p>
        <Link href={`/signin?next=${encodeURIComponent(pathname)}`} className="i-btn i-btn--primary">
          Sign in
        </Link>
        <Link href="/signup" className="i-btn">
          Create an account
        </Link>
      </Gate>
    );
  }
  if (!account) {
    return (
      <Gate title="We couldn't load your account">
        <p className="muted">{accountError}</p>
        <button type="button" className="i-btn" onClick={() => void reloadAccount()}>
          Try again
        </button>
      </Gate>
    );
  }

  const orgs = account.organizations;
  if (parts[0] === "new" || orgs.length === 0) {
    return <NewWorkspace first={!orgs.some((o) => !o.personal)} onCreated={(id) => void reloadAccount().then(() => navigate(`/app/${id}/overview`))} />;
  }
  const requested = parts[0];
  if (!requested) {
    const last = readLastOrg();
    const team = orgs.find((o) => !o.personal);
    const target = orgs.find((o) => o.id === last) ?? team;
    if (!target) return <NewWorkspace first onCreated={(id) => void reloadAccount().then(() => navigate(`/app/${id}/overview`))} />;
    return <Redirect to={`/app/${target.id}/overview`} />;
  }
  const org = orgs.find((o) => o.id === requested);
  if (!org) {
    return (
      <Gate title="Workspace not found">
        <p className="muted">It doesn't exist or you aren't a member.</p>
        <Link href="/app" className="i-btn">
          Go to your workspace
        </Link>
      </Gate>
    );
  }
  return <Workspace key={org.id} orgId={org.id} section={parts[1] ?? "overview"} itemId={parts[2] ?? null} />;
}

function Redirect({ to }: { to: string }) {
  useEffect(() => navigate(to, { replace: true }), [to]);
  return null;
}

function Gate({ title, children }: { title: string; children: React.ReactNode }) {
  useEffect(() => {
    document.title = "VeriFact Intelligence";
  }, []);
  return (
    <div className="intel">
      <div className="i-panel i-gate">
        <span className="i-brand-mark" style={{ margin: "0 auto" }} aria-hidden="true">
          V
        </span>
        <h1>{title}</h1>
        {children}
        <p className="small muted">
          <Link href="/">Back to VeriFact</Link>
        </p>
      </div>
    </div>
  );
}

function NewWorkspace({ first, onCreated }: { first: boolean; onCreated: (id: string) => void }) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const ws = await createWorkspace(name);
      rememberOrg(ws.id);
      onCreated(ws.id);
    } catch (err) {
      setError(message(err));
      setBusy(false);
    }
  }
  return (
    <Gate title={first ? "Create your company workspace" : "Create a workspace"}>
      <p className="muted">
        A workspace holds one company's or client's monitoring, incidents, investigations and reports. You can invite your team after
        creating it.
      </p>
      <form onSubmit={submit} className="i-stack-sm" style={{ textAlign: "left" }}>
        <label className="i-field">
          <span>Company or client name</span>
          <input className="i-input" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required autoFocus />
        </label>
        {error && <Notice tone="bad">{error}</Notice>}
        <button type="submit" className="i-btn i-btn--primary" disabled={busy || !name.trim()}>
          {busy ? "Creating…" : "Create workspace"}
        </button>
      </form>
    </Gate>
  );
}

function Workspace({ orgId, section, itemId }: { orgId: string; section: string; itemId: string | null }) {
  const { account } = useAuth();
  const api = useMemo(() => orgApi(orgId), [orgId]);
  const ws = useLoad(() => api.workspace(), orgId);
  const [navOpen, setNavOpen] = useState(false);
  // Navigating closes the phone navigation drawer.
  const [navFor, setNavFor] = useState(`${section}/${itemId}`);
  if (navFor !== `${section}/${itemId}`) {
    setNavFor(`${section}/${itemId}`);
    setNavOpen(false);
  }
  const role = account?.organizations.find((o) => o.id === orgId)?.role;

  useEffect(() => rememberOrg(orgId), [orgId]);
  useEffect(() => {
    const ws = account?.organizations.find((o) => o.id === orgId)?.name ?? "";
    document.title = `${TITLES[section] ?? "VeriFact Intelligence"} · ${ws} · VeriFact Intelligence`;
  }, [section, orgId, account]);

  const state: IntelState = useMemo(
    () => ({
      orgId,
      api,
      workspace: ws.data,
      reloadWorkspace: ws.reload,
      can: (r) => roleAtLeast(role, r),
      href: (...p: string[]) => `/app/${orgId}/${p.map(encodeURIComponent).join("/")}`,
    }),
    [orgId, api, ws.data, ws.reload, role],
  );

  const page = renderSection(section, itemId);
  const sub = ws.data?.subscription;

  return (
    <IntelContext.Provider value={state}>
      <div className="intel">
        <a className="skip-link" href="#i-main">
          Skip to content
        </a>
        <div className={`i-shell${navOpen ? " nav-open" : ""}`}>
          <aside className="i-rail" aria-label="Workspace navigation">
            <Link href={`/app/${orgId}/overview`} className="i-brand">
              <span className="i-brand-mark" aria-hidden="true">
                V
              </span>
              <span>
                VeriFact Intelligence
                <small>Evidence &amp; reputation risk</small>
              </span>
            </Link>
            <div className="i-ws">
              <label className="visually-hidden-i" htmlFor="i-ws-select">
                Workspace
              </label>
              <select
                id="i-ws-select"
                value={orgId}
                onChange={(e) => navigate(e.target.value === "__new" ? "/app/new" : `/app/${e.target.value}/overview`)}
              >
                {account?.organizations.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.personal ? "Personal workspace" : o.name}
                  </option>
                ))}
                <option value="__new">+ New workspace…</option>
              </select>
            </div>
            <nav className="i-nav" aria-label="Sections">
              {SECTIONS.map((s) => (
                <Link key={s.id} href={`/app/${orgId}/${s.id}`} aria-current={section === s.id ? "page" : undefined}>
                  {s.icon}
                  {s.label}
                </Link>
              ))}
              <span className="i-nav-label">Workspace</span>
              {ADMIN_SECTIONS.map((s) => (
                <Link key={s.id} href={`/app/${orgId}/${s.id}`} aria-current={section === s.id ? "page" : undefined}>
                  {s.icon}
                  {s.label}
                </Link>
              ))}
            </nav>
            <div className="i-rail-foot">
              {sub && (
                <span>
                  {sub.plan ? sub.plan.name : "No plan"} · {sub.status === "TRIALING" ? "trial" : sub.status.toLowerCase().replace("_", " ")}
                </span>
              )}
              <span>{account?.email}</span>
              <Link href="/">VeriFact consumer tools</Link>
            </div>
          </aside>
          <div className="i-main">
            <div className="i-topbar">
              <button type="button" aria-label="Open navigation" aria-expanded={navOpen} onClick={() => setNavOpen((o) => !o)}>
                {Icons.menu}
              </button>
              <strong>{TITLES[section] ?? "VeriFact Intelligence"}</strong>
            </div>
            {navOpen && <button type="button" className="i-scrim" aria-label="Close navigation" onClick={() => setNavOpen(false)} />}
            <main id="i-main" className="i-content" tabIndex={-1}>
              {ws.error && ws.status !== 404 && <Notice tone="bad">{ws.error}</Notice>}
              {page}
            </main>
          </div>
        </div>
      </div>
    </IntelContext.Provider>
  );
}

function renderSection(section: string, id: string | null) {
  switch (section) {
    case "overview":
      return <OverviewPage />;
    case "monitoring":
      return id ? <ProjectPage key={id} id={id} /> : <MonitoringPage />;
    case "incidents":
      return id ? <IncidentPage key={id} id={id} /> : <IncidentsPage />;
    case "investigations":
      return id ? <InvestigationPage key={id} id={id} /> : <InvestigationsPage />;
    case "evidence":
      return <EvidencePage />;
    case "responses":
      return id ? <ResponsePage key={id} id={id} /> : <ResponsesPage />;
    case "reports":
      return id ? <ReportPage key={id} id={id} /> : <ReportsPage />;
    case "settings":
      return <SettingsPage />;
    case "billing":
      return <BillingPage />;
    default:
      return (
        <Notice tone="warn" title="Page not found">
          That section doesn't exist. Use the navigation to continue.
        </Notice>
      );
  }
}
