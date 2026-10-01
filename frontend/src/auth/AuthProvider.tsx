import type { AuthChangeEvent, Session } from "@supabase/auth-js";
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { getAccount, type Account } from "./api";
import { auth, authEnabled } from "./client";
import { AuthContext, type AuthState } from "./useAuth";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(!authEnabled);
  const [session, setSession] = useState<Session | null>(null);
  const [recovering, setRecovering] = useState(false);
  const [account, setAccount] = useState<Account | null>(null);
  const [accountError, setAccountError] = useState<string | null>(null);

  useEffect(() => {
    if (!auth) return;
    let active = true;
    // getSession waits for the client to finish handling a code in the URL (sign-in, confirmation, reset).
    void auth.getSession().then(({ data }: { data: { session: Session | null } }) => {
      if (!active) return;
      setSession(data.session);
      setReady(true);
    });
    // Only set state here: the auth client must not be called from inside this callback.
    const { data } = auth.onAuthStateChange((event: AuthChangeEvent, next: Session | null) => {
      setSession(next);
      if (event === "PASSWORD_RECOVERY") setRecovering(true);
      if (event === "SIGNED_OUT") setRecovering(false);
    });
    return () => {
      active = false;
      data.subscription.unsubscribe();
    };
  }, []);

  const userId = session?.user.id ?? null;
  const reloadAccount = useCallback(async () => {
    try {
      setAccountError(null);
      setAccount(await getAccount());
    } catch (e) {
      setAccountError(e instanceof Error ? e.message : "We couldn't load your account.");
    }
  }, []);

  useEffect(() => {
    if (!userId) return;
    let active = true;
    getAccount().then(
      (a) => {
        if (!active) return;
        setAccountError(null);
        setAccount(a);
      },
      (e: unknown) => active && setAccountError(e instanceof Error ? e.message : "We couldn't load your account."),
    );
    return () => {
      active = false;
    };
  }, [userId]);

  // An account loaded for an earlier session (before signing out or switching users) is never shown.
  const current = account && userId && account.id === userId ? account : null;
  const value = useMemo<AuthState>(
    () => ({ enabled: authEnabled, ready, session, recovering, account: current, accountError, setAccount, reloadAccount }),
    [ready, session, recovering, current, accountError, reloadAccount],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
