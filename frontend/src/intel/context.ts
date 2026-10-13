import { createContext, useContext } from "react";
import type { Role } from "../auth/api";
import type { OrgApi } from "./api";
import type { Workspace } from "./types";

export interface IntelState {
  orgId: string;
  api: OrgApi;
  workspace: Workspace | null;
  reloadWorkspace: () => void;
  /** True when the caller's role is at least {@code role} (the server checks again on every call). */
  can: (role: Role) => boolean;
  /** Path inside the current workspace, e.g. href("incidents", id). */
  href: (...parts: string[]) => string;
}

export const IntelContext = createContext<IntelState | null>(null);

export function useIntel(): IntelState {
  const s = useContext(IntelContext);
  if (!s) throw new Error("useIntel must be used inside IntelApp");
  return s;
}

const RANK: Record<Role, number> = { OWNER: 0, ADMIN: 1, MEMBER: 2 };

export function roleAtLeast(role: Role | undefined, required: Role): boolean {
  return role !== undefined && RANK[role] <= RANK[required];
}
