import { authed } from "../../auth/api";
import type { Folder, FoundSource } from "../student/types";
import type { FileKind, LibraryItem, Project, ProjectFile, ProjectSummary } from "./types";

/** Capstone projects: signed-in only (`authed` sends the session token and refreshes it once on 401). */
const BASE = "/api/v2/me/projects";
const json = (body: unknown): RequestInit => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export async function listProjects(): Promise<ProjectSummary[]> {
  return (await authed(BASE)).json() as Promise<ProjectSummary[]>;
}

export async function createProject(title: string, field: string, country: string | null): Promise<Project> {
  return (await authed(BASE, { method: "POST", ...json({ title, field, country }) })).json() as Promise<Project>;
}

export async function importWorkspace(workspaceId: string, editToken: string): Promise<Project> {
  return (await authed(`${BASE}/import`, { method: "POST", ...json({ workspaceId, editToken }) })).json() as Promise<Project>;
}

export async function getProject(id: string): Promise<Project> {
  return (await authed(`${BASE}/${encodeURIComponent(id)}`)).json() as Promise<Project>;
}

export interface ProjectChanges {
  title?: string;
  field?: string;
  country?: string;
  notes?: string;
  questions?: { id: string | null; text: string }[];
  gaps?: { id: string | null; statement: string; sourceKeys: string[] }[];
}

export async function updateProject(id: string, changes: ProjectChanges): Promise<Project> {
  return (await authed(`${BASE}/${encodeURIComponent(id)}`, { method: "PUT", ...json(changes) })).json() as Promise<Project>;
}

export async function addToLibrary(id: string, source: FoundSource, folder: Folder, questionId: string | null): Promise<Project> {
  const body = { key: source.key, folder, relevance: source.relevance, relevanceQuote: source.relevanceQuote, stance: source.stance, questionId };
  return (await authed(`${BASE}/${encodeURIComponent(id)}/library`, { method: "POST", ...json(body) })).json() as Promise<Project>;
}

export type ItemChanges = Partial<Pick<LibraryItem, "folder" | "status" | "note" | "keyFindings" | "method" | "questionIds">>;

export async function updateLibraryItem(id: string, key: string, changes: ItemChanges): Promise<Project> {
  return (await authed(`${BASE}/${encodeURIComponent(id)}/library`, { method: "PUT", ...json({ key, ...changes }) })).json() as Promise<Project>;
}

export async function removeFromLibrary(id: string, key: string): Promise<Project> {
  const q = new URLSearchParams({ key });
  return (await authed(`${BASE}/${encodeURIComponent(id)}/library?${q}`, { method: "DELETE" })).json() as Promise<Project>;
}

/** Upload a chapter draft or a research paper; the server reads and analyses it (about 15–60 seconds). */
export async function uploadProjectFile(id: string, file: File, kind: FileKind, label: string): Promise<Project> {
  const form = new FormData();
  form.append("file", file);
  form.append("kind", kind);
  if (label.trim()) form.append("label", label.trim());
  return (await authed(`${BASE}/${encodeURIComponent(id)}/files`, { method: "POST", body: form })).json() as Promise<Project>;
}

export async function getProjectFile(id: string, fileId: string): Promise<ProjectFile> {
  return (await authed(`${BASE}/${encodeURIComponent(id)}/files/${encodeURIComponent(fileId)}`)).json() as Promise<ProjectFile>;
}

export async function relabelProjectFile(id: string, fileId: string, label: string): Promise<Project> {
  return (await authed(`${BASE}/${encodeURIComponent(id)}/files/${encodeURIComponent(fileId)}`, { method: "PUT", ...json({ label }) })).json() as Promise<Project>;
}

export async function deleteProjectFile(id: string, fileId: string): Promise<Project> {
  return (await authed(`${BASE}/${encodeURIComponent(id)}/files/${encodeURIComponent(fileId)}`, { method: "DELETE" })).json() as Promise<Project>;
}

export async function generateProjectInsights(id: string): Promise<Project> {
  return (await authed(`${BASE}/${encodeURIComponent(id)}/insights`, { method: "POST" })).json() as Promise<Project>;
}

export async function deleteProject(id: string): Promise<void> {
  await authed(`${BASE}/${encodeURIComponent(id)}`, { method: "DELETE" });
}
