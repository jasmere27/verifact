import { useEffect, useId, useRef, useState } from "react";
import Link from "../components/Link";
import { navigate, usePathname } from "../router";
import { shownName, useAuth } from "./useAuth";
import { auth } from "./client";

/** Header control: "Sign in" when signed out; the account's initial with a small menu when signed in. */
export default function AccountMenu() {
  const { enabled, ready, session, account } = useAuth();
  const pathname = usePathname();
  // The menu is open only on the page where it was opened, so navigating closes it.
  const [openOn, setOpenOn] = useState<string | null>(null);
  const open = openOn === pathname;
  const setOpen = (value: boolean | ((was: boolean) => boolean)) =>
    setOpenOn((was) => ((typeof value === "function" ? value(was === pathname) : value) ? pathname : null));
  const menuId = useId();
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === "Escape" : !rootRef.current?.contains(event.target as Node)) {
        setOpenOn(null);
      }
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  if (!enabled || !ready) return null;
  if (!session) {
    if (/^\/(signin|signup|auth\/callback)/.test(pathname)) return null;
    const next = pathname === "/" ? "" : `?next=${encodeURIComponent(pathname)}`;
    return (
      <Link href={`/signin${next}`} className="button button--secondary button--small account-signin">
        Sign in
      </Link>
    );
  }

  const name = shownName(account, session);
  return (
    <div className="account-menu" ref={rootRef}>
      <button
        type="button"
        className="account-trigger"
        aria-expanded={open}
        aria-controls={menuId}
        aria-label={`Account: ${name}`}
        onClick={() => setOpen((o) => !o)}
      >
        <span className="account-avatar" aria-hidden="true">
          {name.charAt(0).toUpperCase()}
        </span>
      </button>
      {open && (
        <div id={menuId} className="account-dropdown">
          <p className="account-dropdown-who">
            <strong>{name}</strong>
            <span className="muted small">{account?.email ?? session.user.email}</span>
          </p>
          <Link href="/account" className="account-dropdown-item">
            Account
          </Link>
          <button
            type="button"
            className="account-dropdown-item"
            onClick={() => {
              setOpen(false);
              void auth?.signOut({ scope: "local" }).then(() => navigate("/", { replace: true }));
            }}
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
