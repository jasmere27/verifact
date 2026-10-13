import { authed } from "../auth/api";
import type {
  AuditEntry,
  BillingState,
  Capabilities,
  DraftDetail,
  DraftSummary,
  EvidenceRecord,
  IncidentDetail,
  IncidentSummary,
  InvestigationDetail,
  InvestigationResult,
  InvestigationSummary,
  Member,
  Mention,
  MonitoringProject,
  MonitoringRun,
  Overview,
  Paged,
  ProjectInput,
  Report,
  ReportSummary,
  Subscription,
  Workspace,
} from "./types";

/** VeriFact Intelligence: every call is signed in and scoped to one workspace (checked on the server). */
const json = (body: unknown): RequestInit => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
const enc = encodeURIComponent;

async function getJson<T>(path: string): Promise<T> {
  return (await authed(path)).json() as Promise<T>;
}

async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await authed(path, body === undefined ? { method } : { method, ...json(body) });
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== "") q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : "";
}

export function orgApi(orgId: string) {
  const base = `/api/v2/me/orgs/${enc(orgId)}`;
  return {
    workspace: () => getJson<Workspace>(base),
    rename: (name: string) => send<Workspace>("PUT", base, { name }),
    overview: () => getJson<Overview>(`${base}/overview`),
    capabilities: () => getJson<Capabilities>(`${base}/capabilities`),
    members: () => getJson<Member[]>(`${base}/members`),
    addMember: (email: string, role: string) => send<Member>("POST", `${base}/members`, { email, role }),
    changeRole: (userId: string, role: string) => send<Member[]>("PUT", `${base}/members/${enc(userId)}`, { role }),
    removeMember: (userId: string) => send<void>("DELETE", `${base}/members/${enc(userId)}`),
    audit: () => getJson<AuditEntry[]>(`${base}/audit`),

    billing: () => getJson<BillingState>(`${base}/billing`),
    startTrial: () => send<Subscription>("POST", `${base}/billing/trial`),
    choosePlan: (planId: string) => send<{ checkoutUrl: string | null; message: string | null }>("POST", `${base}/billing/checkout`, { planId }),
    cancel: () => send<{ message: string }>("POST", `${base}/billing/cancel`),
    portal: () => send<{ url: string }>("POST", `${base}/billing/portal`),

    projects: () => getJson<MonitoringProject[]>(`${base}/projects`),
    project: (id: string) => getJson<MonitoringProject>(`${base}/projects/${enc(id)}`),
    createProject: (input: ProjectInput) => send<MonitoringProject>("POST", `${base}/projects`, input),
    updateProject: (id: string, input: ProjectInput) => send<MonitoringProject>("PUT", `${base}/projects/${enc(id)}`, input),
    setProjectStatus: (id: string, status: "ACTIVE" | "PAUSED") => send<MonitoringProject>("POST", `${base}/projects/${enc(id)}/status`, { status }),
    deleteProject: (id: string) => send<void>("DELETE", `${base}/projects/${enc(id)}`),
    runNow: (id: string) => send<MonitoringRun>("POST", `${base}/projects/${enc(id)}/run`),
    runs: (projectId?: string, limit = 30) => getJson<MonitoringRun[]>(`${base}/runs${query({ projectId, limit })}`),
    mentions: (p: { projectId?: string; status?: string; relevance?: string; withClaim?: boolean; page?: number; size?: number }) =>
      getJson<Paged<Mention>>(`${base}/mentions${query(p)}`),
    mentionStatus: (id: string, status: string) => send<Mention>("POST", `${base}/mentions/${enc(id)}/status`, { status }),
    escalate: (id: string) => send<{ incidentId: string }>("POST", `${base}/mentions/${enc(id)}/escalate`),

    incidents: (p: { status?: string; priority?: string; projectId?: string; ownerId?: string; q?: string; sort?: string; page?: number; size?: number }) =>
      getJson<Paged<IncidentSummary>>(`${base}/incidents${query(p)}`),
    incident: (id: string) => getJson<IncidentDetail>(`${base}/incidents/${enc(id)}`),
    createIncident: (input: { title?: string; claim: string; discoveryUrl?: string; projectId?: string; severity?: string }) =>
      send<IncidentDetail>("POST", `${base}/incidents`, input),
    updateIncident: (id: string, changes: { title?: string; status?: string; ownerId?: string; priority?: string; nextSteps?: string }) =>
      send<IncidentDetail>("PATCH", `${base}/incidents/${enc(id)}`, changes),
    addNote: (id: string, note: string) => send<IncidentDetail>("POST", `${base}/incidents/${enc(id)}/notes`, { note }),
    deleteIncident: (id: string) => send<void>("DELETE", `${base}/incidents/${enc(id)}`),

    investigations: (p: { incidentId?: string; page?: number; size?: number }) =>
      getJson<Paged<InvestigationSummary>>(`${base}/investigations${query(p)}`),
    investigation: (id: string) => getJson<InvestigationDetail>(`${base}/investigations/${enc(id)}`),
    investigationVersion: (id: string, v: number) => getJson<InvestigationResult>(`${base}/investigations/${enc(id)}/versions/${v}`),
    startInvestigation: (input: { title?: string; input: string; incidentId?: string }) =>
      send<InvestigationDetail>("POST", `${base}/investigations`, input),
    rerun: (id: string) => send<InvestigationDetail>("POST", `${base}/investigations/${enc(id)}/rerun`),
    saveNotes: (id: string, notes: string | null, markReviewed: boolean) =>
      send<InvestigationDetail>("PUT", `${base}/investigations/${enc(id)}/notes`, { notes, markReviewed }),
    deleteInvestigation: (id: string) => send<void>("DELETE", `${base}/investigations/${enc(id)}`),

    evidence: (p: { q?: string; direction?: string; sourceType?: string; investigationId?: string; page?: number; size?: number }) =>
      getJson<Paged<EvidenceRecord>>(`${base}/evidence${query(p)}`),

    drafts: (p: { investigationId?: string; incidentId?: string; status?: string }) => getJson<DraftSummary[]>(`${base}/responses${query(p)}`),
    draft: (id: string) => getJson<DraftDetail>(`${base}/responses/${enc(id)}`),
    generateDraft: (input: { investigationId: string; kind: string; speakingFor?: string; incidentId?: string }) =>
      send<DraftDetail>("POST", `${base}/responses`, input),
    editDraft: (id: string, edit: { body?: string; title?: string; note?: string }) => send<DraftDetail>("PUT", `${base}/responses/${enc(id)}`, edit),
    draftStatus: (id: string, status: string) => send<DraftDetail>("POST", `${base}/responses/${enc(id)}/status`, { status }),
    deleteDraft: (id: string) => send<void>("DELETE", `${base}/responses/${enc(id)}`),

    reports: () => getJson<ReportSummary[]>(`${base}/reports`),
    report: (id: string) => getJson<Report>(`${base}/reports/${enc(id)}`),
    generateReport: (days: number, title?: string) => send<Report>("POST", `${base}/reports`, { days, title }),
    deleteReport: (id: string) => send<void>("DELETE", `${base}/reports/${enc(id)}`),
    /** Downloads the PDF through the authenticated API (the browser can't attach the token to a plain link). */
    downloadReportPdf: async (id: string, fileName: string) => {
      const blob = await (await authed(`${base}/reports/${enc(id)}/pdf`)).blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    },
  };
}

export type OrgApi = ReturnType<typeof orgApi>;

export function createWorkspace(name: string): Promise<Workspace> {
  return send<Workspace>("POST", "/api/v2/me/orgs", { name });
}
