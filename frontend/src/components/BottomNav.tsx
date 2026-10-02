import { useState } from "react";
import { auth } from "../auth/client";
import { shownName, useAuth } from "../auth/useAuth";
import { CONTACT_EMAIL } from "../policies/PolicyPages";
import { useInstall } from "../install";
import type { Route } from "../router";
import { navigate, usePathname } from "../router";
import BottomSheet from "./BottomSheet";
import Link from "./Link";
import ShareSite from "./ShareSite";
import TabIcon from "./TabIcons";
import type { TabIconName } from "./TabIcons";
import { ThemeMenuSwitch } from "./ThemeToggle";

const TABS: { href: string; label: string; icon: TabIconName; current: Route["name"][] }[] = [
  { href: "/", label: "Home", icon: "home", current: ["landing"] },
  { href: "/check", label: "Check", icon: "check", current: ["check", "report"] },
  { href: "/research", label: "Research", icon: "research", current: ["research", "researchWorkspace", "researchProject"] },
  { href: "/news", label: "News", icon: "news", current: ["news", "newsWorkspace"] },
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
              <TabIcon name={t.icon} active={current} />
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
          <TabIcon name="more" active={moreCurrent || open} />
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
