import type { Session } from "@supabase/auth-js";
import { createContext, useContext } from "react";
import type { Account } from "./api";

export interface AuthState {
  /** Accounts are configured for this build. */
  enabled: boolean;
  /** The stored session has been read (and any link in the URL handled). */
  ready: boolean;
  session: Session | null;
  /** Arrived through a password-reset link: the reset page may set a new password. */
  recovering: boolean;
  account: Account | null;
  accountError: string | null;
  setAccount: (account: Account) => void;
  reloadAccount: () => Promise<void>;
}


export const AuthContext = createContext<AuthState | null>(null);

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error("useAuth must be used inside AuthProvider");
  return state;
}

/** The name to show: display name, else the email's local part. */
export function shownName(account: Account | null, session: Session | null): string {
  const name = account?.displayName?.trim();
  if (name) return name;
  const email = account?.email ?? session?.user.email ?? "";
  return email.split("@")[0] || "Account";
}
