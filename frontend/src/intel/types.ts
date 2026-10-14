/** VeriFact Intelligence API types (mirrors the backend records in com.ai.agent.verifact.intelligence). */
import type { Role } from "../auth/api";

export type Feature = "MONITORING_RUN" | "INVESTIGATION" | "RESPONSE_DRAFT" | "REPORT";
export type SubscriptionStatus = "NONE" | "TRIALING" | "ACTIVE" | "PAST_DUE" | "CANCELED" | "EXPIRED";

export interface Plan {
  id: string;
  name: string;
  priceUsdMonthly: number;
  summary: string;
  maxProjects: number;
  maxTermsPerProject: number;
  minCheckIntervalHours: number;
  monitoringRunsPerMonth: number;
  investigationsPerMonth: number;
  responseDraftsPerMonth: number;
  reportsPerMonth: number;
  seats: number;
  maxRssFeedsPerProject: number;
  purchasable: boolean;
}

export interface Subscription {
  organizationId: string;
  status: SubscriptionStatus;
  plan: Plan | null;
  provider: string | null;
  trialEndsAt: string | null;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
  trialAvailable: boolean;
  entitled: boolean;
}

export interface Costs {
  llmCalls: number;
  promptTokens: number;
  completionTokens: number;
  searchCalls: number;
}

export interface Usage {
  periodStart: string;
  periodEnd: string;
  features: { feature: Feature; used: number; limit: number }[];
  costs: Costs;
  costsByFeature: Partial<Record<Feature, Costs>>;
}

export interface BillingState {
  subscription: Subscription;
  usage: Usage;
  plans: Plan[];
  onlinePayment: boolean;
}

export interface Member {
  userId: string;
  email: string | null;
  displayName: string | null;
  role: Role;
  joinedAt: string;
}

export interface Workspace {
  id: string;
  name: string;
  personal: boolean;
  yourRole: Role;
  members: Member[];
  seats: number;
  subscription: Subscription;
}

export interface Capabilities {
  sources: Record<string, boolean>;
  onlinePayment: boolean;
  billingProvider: string;
  scheduler: boolean;
  externalCron: boolean;
  emailNotifications: boolean;
}

export interface MonitoringConfig {
  aliases: string[];
  products: string[];
  executives: string[];
  keywords: string[];
  topics: string[];
  excludedTerms: string[];
  sources: { webSearch: boolean; newsSearch: boolean; rssFeeds: string[]; pages: string[] };
  lookbackDays: number;
  alertThreshold: "HIGH" | "MEDIUM" | "NONE";
}

export type Health = "NEVER_RUN" | "HEALTHY" | "DEGRADED" | "FAILING" | "PAUSED";

export interface MonitoringProject {
  id: string;
  name: string;
  brand: string;
  config: MonitoringConfig;
  status: "ACTIVE" | "PAUSED";
  checkIntervalHours: number;
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastSuccessAt: string | null;
  consecutiveFailures: number;
  createdAt: string;
  updatedAt: string;
  health: Health;
}

export interface ProjectInput {
  name: string;
  brand: string;
  aliases: string[];
  products: string[];
  executives: string[];
  keywords: string[];
  topics: string[];
  excludedTerms: string[];
  webSearch: boolean;
  newsSearch: boolean;
  rssFeeds: string[];
  pages: string[];
  lookbackDays: number;
  alertThreshold: string;
  checkIntervalHours: number;
}

export interface SourceOutcome {
  connector: string;
  status: "OK" | "PARTIAL" | "FAILED" | "UNAVAILABLE";
  items: number;
  relevant: number;
  detail: string | null;
}

export type RunStatus = "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "SKIPPED";

export interface MonitoringRun {
  id: string;
  projectId: string;
  projectName: string;
  trigger: "SCHEDULED" | "MANUAL" | "RETRY";
  status: RunStatus;
  attempt: number;
  scheduledFor: string | null;
  startedAt: string;
  finishedAt: string | null;
  resultsFound: number;
  newMentions: number;
  newIncidents: number;
  sources: SourceOutcome[];
  errorSummary: string | null;
}

export interface Mention {
  id: string;
  projectId: string;
  projectName: string;
  connector: string;
  url: string;
  domain: string | null;
  title: string | null;
  snippet: string | null;
  publishedDate: string | null;
  relevance: "HIGH" | "MEDIUM" | "LOW";
  matchedTerms: string[];
  claim: string | null;
  claimQuote: string | null;
  claimSeverity: string | null;
  status: "NEW" | "REVIEWED" | "DISMISSED" | "ESCALATED";
  incidentId: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  seenCount: number;
}

export interface Paged<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export type IncidentStatus = "NEW" | "NEEDS_REVIEW" | "INVESTIGATING" | "AWAITING_EVIDENCE" | "RESPONSE_DRAFTED" | "RESOLVED" | "MONITORING";
export type Priority = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";

export interface IncidentSummary {
  id: string;
  title: string;
  claim: string;
  status: IncidentStatus;
  priority: Priority;
  riskScore: number;
  projectId: string | null;
  projectName: string | null;
  ownerId: string | null;
  ownerEmail: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  updatedAt: string;
  mentionCount: number;
  investigationId: string | null;
}

export interface RiskFactor {
  name: string;
  points: number;
  max: number;
  reason: string;
}

export interface RiskAssessment {
  score: number;
  priority: Priority;
  factors: RiskFactor[];
  inputs: { severity: string | null; evidence: string | null };
  method: string;
}

export interface IncidentDetail {
  summary: IncidentSummary;
  discoveryUrl: string | null;
  risk: RiskAssessment;
  priorityOverride: boolean;
  nextSteps: string | null;
  suggestedSteps: string[];
  events: { id: string; kind: string; detail: string | null; actorId: string | null; actorEmail: string | null; createdAt: string }[];
  mentions: {
    id: string;
    url: string;
    domain: string | null;
    title: string | null;
    snippet: string | null;
    publishedDate: string | null;
    connector: string;
    relevance: string;
    claimQuote: string | null;
    firstSeenAt: string;
  }[];
  related: IncidentSummary[];
  investigations: { id: string; title: string; status: string; version: number; updatedAt: string }[];
}

export type Assessment = "SUPPORTED" | "CONTRADICTED" | "MIXED" | "INSUFFICIENT_EVIDENCE" | "MISSING_CONTEXT" | "UNDER_INVESTIGATION";

export interface Excerpt {
  sourceId: string;
  text: string;
  url: string;
  domain: string;
  title: string;
}

export interface ClaimResult {
  id: string;
  type: string;
  claim: string;
  originalWords: string | null;
  speaker: string | null;
  assessment: Assessment;
  verdict: string;
  supporting: Excerpt | null;
  contradicting: Excerpt | null;
  sourcesConflict: boolean;
  contextIssue: string;
  quoteStatus: string;
  quoteSource: Excerpt | null;
  aiExplanation: string | null;
}

export interface EvidenceSource {
  id: string;
  url: string;
  domain: string;
  title: string;
  publishedDate: string | null;
  retrievedAt: string;
  sourceType: string;
  snippet: string;
  direction: "SUPPORTS" | "CONTRADICTS" | "MIXED" | "CONTEXT";
  claimIds: string[];
}

export interface InvestigationResult {
  version: number;
  createdAt: string;
  inputUrl: string | null;
  inputTitle: string | null;
  claims: ClaimResult[];
  sources: EvidenceSource[];
  chronology: { date: string; label: string; sourceId: string | null }[];
  unresolved: string[];
  limitations: string[];
  method: string;
  searchProvider: string;
  durationMs: number;
}

export interface Changes {
  previousVersion: number;
  claims: { claim: string; before: string | null; after: string }[];
  newSources: string[];
  droppedSources: string[];
  summary: string;
}

export interface InvestigationSummary {
  id: string;
  title: string;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";
  version: number;
  incidentId: string | null;
  incidentTitle: string | null;
  headline: Assessment | null;
  createdAt: string;
  updatedAt: string;
  lastReviewedAt: string | null;
}

export interface InvestigationDetail {
  summary: InvestigationSummary;
  input: string;
  errorMessage: string | null;
  analystNotes: string | null;
  result: InvestigationResult | null;
  changes: Changes | null;
  versions: { version: number; createdAt: string; claims: number; sources: number; changes: Changes | null }[];
}

export interface EvidenceRecord {
  id: string;
  investigationId: string;
  investigationTitle: string;
  version: number;
  sourceRef: string;
  url: string;
  domain: string | null;
  title: string | null;
  snippet: string | null;
  publishedDate: string | null;
  retrievedAt: string;
  sourceType: string | null;
  direction: "SUPPORTS" | "CONTRADICTS" | "MIXED" | "CONTEXT";
  claimRefs: string | null;
  excerpt: string | null;
}

export type DraftKind =
  | "INTERNAL_BRIEFING"
  | "EXECUTIVE_SUMMARY"
  | "HOLDING_STATEMENT"
  | "CUSTOMER_COMMUNICATION"
  | "PUBLIC_RESPONSE"
  | "CORRECTIVE_INFORMATION"
  | "FAQ"
  | "ESCALATION_CHECKLIST";

export type DraftStatus = "DRAFT" | "IN_REVIEW" | "APPROVED";

export interface DraftSummary {
  id: string;
  kind: DraftKind;
  kindLabel: string;
  title: string;
  status: DraftStatus;
  revision: number;
  investigationId: string | null;
  investigationTitle: string | null;
  investigationVersion: number | null;
  incidentId: string | null;
  createdAt: string;
  updatedAt: string;
  approvedByEmail: string | null;
  approvedAt: string | null;
}

export interface DraftDetail {
  summary: DraftSummary;
  body: string;
  meta: {
    keyFacts: { text: string; sourceIds: string[] }[];
    uncertainties: string[];
    nextSteps: string[];
    citations: { sourceId: string; url: string; domain: string; title: string }[];
    evidenceWarning: string | null;
    removedForGrounding: number;
    aiGenerated: boolean;
  };
  revisions: { revision: number; body: string; editedBy: string | null; editedByEmail: string | null; note: string | null; createdAt: string }[];
}

export interface ReportSummary {
  id: string;
  title: string;
  periodStart: string;
  periodEnd: string;
  generatedAt: string;
  generatedBy: string | null;
}

export interface Report {
  id: string;
  title: string;
  workspaceName: string;
  periodStart: string;
  periodEnd: string;
  generatedAt: string;
  generatedBy: string | null;
  executiveSummary: string[];
  coverage: {
    projects: { name: string; brand: string; status: string; sources: string[]; runs: number; failedRuns: number; lastSuccessAt: string | null }[];
    runs: number;
    failedRuns: number;
    partialRuns: number;
  };
  mentions: { total: number; byRelevance: Record<string, number>; bySource: Record<string, number>; withClaims: number };
  importantMentions: { title: string | null; url: string; domain: string | null; publishedDate: string | null; firstSeenAt: string; relevance: string; aiClaim: string | null }[];
  incidents: { opened: number; resolved: number; openNow: number; openByPriority: Record<string, number>; openByStatus: Record<string, number> };
  openIncidents: { title: string; priority: Priority; riskScore: number; status: IncidentStatus; owner: string | null; firstSeenAt: string; mentions: number }[];
  findings: { investigation: string; version: number; claim: string; assessment: Assessment; excerpt: string | null; citationUrl: string | null; citationDomain: string | null; assessedAt: string }[];
  themes: { term: string; mentions: number }[];
  evidenceChanges: { investigation: string; version: number; at: string; summary: string }[];
  recommendedActions: string[];
  unresolved: string[];
  limitations: string[];
  disclaimer: string;
}

export interface SourceHealth {
  projectId: string;
  projectName: string;
  brand: string;
  health: Health;
  status: "ACTIVE" | "PAUSED";
  lastRunAt: string | null;
  lastSuccessAt: string | null;
  nextRunAt: string | null;
  lastRunStatus: RunStatus | null;
  lastSources: SourceOutcome[];
  lastError: string | null;
}

export interface Overview {
  subscription: Subscription;
  usage: Usage;
  activeProjects: number;
  pausedProjects: number;
  newMentions7d: number;
  unreviewedMentions: number;
  claimsFlagged7d: number;
  incidentsNeedingReview: number;
  openIncidents: number;
  openByPriority: Record<Priority, number>;
  topIncidents: IncidentSummary[];
  recentInvestigations: InvestigationSummary[];
  recentRuns: MonitoringRun[];
  sourceHealth: SourceHealth[];
  evidenceRecords7d: number;
  changedInvestigations7d: number;
  onboarding: boolean;
}

export interface AuditEntry {
  id: string;
  actorId: string | null;
  actorEmail: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  detail: string | null;
  createdAt: string;
}
