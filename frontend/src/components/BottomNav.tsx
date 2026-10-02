import { useState } from "react";
import type { ReactNode } from "react";
import { auth } from "../auth/client";
import { shownName, useAuth } from "../auth/useAuth";
import { CONTACT_EMAIL } from "../policies/PolicyPages";
import { useInstall } from "../install";
import type { Route } from "../router";
import { navigate, usePathname } from "../router";
import BottomSheet from "./BottomSheet";
import Link from "./Link";
import ShareSite from "./ShareSite";
import { ThemeMenuSwitch } from "./ThemeToggle";

const icon = (d: ReactNode) => (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    {d}
  </svg>
);

const ICONS = {
  home: icon(<path d="M3 11l9-7 9 7M5 10v10h5v-6h4v6h5V10" />),
  check: icon(
    <>
      <path d="M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6z" />
      <path d="M8.5 12l2.5 2.5 4.5-5" />
    </>,
  ),
  research: icon(
    <>
      <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z" />
      <path d="M4 20.5A2.5 2.5 0 0 0 6.5 23H20v-5M9 7h7M9 11h5" />
    </>,
  ),
  news: icon(
    <>
      <path d="M4 5h13v14a2 2 0 0 0 2 2H6a2 2 0 0 1-2-2z" />
      <path d="M17 9h3v10a2 2 0 0 1-2 2M8 9h5M8 13h5M8 17h3" />
    </>,
  ),
  more: icon(
    <>
      <circle cx="5" cy="12" r="1.3" />
      <circle cx="12" cy="12" r="1.3" />
      <circle cx="19" cy="12" r="1.3" />
    </>,
  ),
};

const TABS: { href: string; label: string; icon: ReactNode; current: Route["name"][] }[] = [
  { href: "/", label: "Home", icon: ICONS.home, current: ["landing"] },
  { href: "/check", label: "Check", icon: ICONS.check, current: ["check", "report"] },
  { href: "/research", label: "Research", icon: ICONS.research, current: ["research", "researchWorkspace", "researchProject"] },
  { href: "/news", label: "News", icon: ICONS.news, current: ["news", "newsWorkspace"] },
];

/** The phone tab bar: main products one tap away, everything else under More (a bottom sheet). */
export default function BottomNav({ route }: { route: Route["name"] }) {
  const pathname = usePathname();
  // Open only on the page where it was opened, so following a link inside closes it.
  const [openOn, setOpenOn] = useState<string | null>(null);
  const open = openOn === pathname;
  const moreCurrent = ["legal", "account", "install", "privacy", "terms", "about"].includes(route);

  return (
    <>
      <nav className="bottom-nav" aria-label="Main">
        {TABS.map((t) => {
          const current = t.current.includes(route);
          return (
            <Link key={t.href} href={t.href} className="bottom-nav-item" aria-current={current ? "page" : undefined}>
              {t.icon}
              <span>{t.label}</span>
            </Link>
          );
        })}
        <button
          type="button"
          className="bottom-nav-item"
          aria-haspopup="dialog"
          aria-expanded={open}
          data-current={moreCurrent || undefined}
          onClick={() => setOpenOn(pathname)}
        >
          {ICONS.more}
          <span>More</span>
        </button>
      </nav>
      <BottomSheet open={open} title="More" onClose={() => setOpenOn(null)}>
        <MoreContent onDone={() => setOpenOn(null)} />
      </BottomSheet>
    </>
  );
}

function MoreContent({ onDone }: { onDone: () => void }) {
  const { enabled, session, account } = useAuth();
  const { installed } = useInstall();
  return (
    <>
      {enabled && (
        <div className="more-account">
          {session ? (
            <>
              <span className="account-avatar" aria-hidden="true">
                {shownName(account, session).charAt(0).toUpperCase()}
              </span>
              <span className="more-account-who">
                <strong>{shownName(account, session)}</strong>
                <span className="muted small">{account?.email ?? session.user.email}</span>
              </span>
              <Link href="/account" className="button button--secondary button--small">
                Account
              </Link>
            </>
          ) : (
            <>
              <span className="more-account-who">
                <strong>Save your work</strong>
                <span className="muted small">Sign in to keep checks and capstone projects on any device.</span>
              </span>
              <Link href="/signin" className="button button--primary button--small">
                Sign in
              </Link>
            </>
          )}
        </div>
      )}

      <ul className="more-list">
        <li>
          <Link href="/legal" className="more-item">
            <span className="more-item-label">LegalFact</span>
            <span className="more-item-hint">Find official legal sources for a situation</span>
          </Link>
        </li>
        {!installed && (
          <li>
            <Link href="/install" className="more-item">
              <span className="more-item-label">Get the app</span>
              <span className="more-item-hint">Add VeriFact to your home screen</span>
            </Link>
          </li>
        )}
        <li>
          <ShareSite className="more-item more-item--button" label="Share VeriFact with friends" />
        </li>
        <li>
          <ThemeMenuSwitch />
        </li>
      </ul>

      <ul className="more-links">
        <li>
          <Link href="/about">How VeriFact works</Link>
        </li>
        <li>
          <Link href="/privacy">Privacy Policy</Link>
        </li>
        <li>
          <Link href="/terms">Terms of Use</Link>
        </li>
        <li>
          <a href={`mailto:${CONTACT_EMAIL}`}>Contact</a>
        </li>
        {session && (
          <li>
            <button
              type="button"
              className="text-button"
              onClick={() => {
                onDone();
                void auth?.signOut({ scope: "local" }).then(() => navigate("/", { replace: true }));
              }}
            >
              Sign out
            </button>
          </li>
        )}
      </ul>
      <p className="more-note">An aid for checking claims against published sources, not a final authority. LegalFact gives legal information, not legal advice.</p>
    </>
  );
}
