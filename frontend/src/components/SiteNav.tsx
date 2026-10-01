import { useEffect, useId, useRef, useState } from "react";
import type { Route } from "../router";
import { usePathname } from "../router";
import Link from "./Link";
import ShareSite from "./ShareSite";
import { useInstall } from "../install";

const PRODUCTS: { href: string; label: string; hint: string; current: Route["name"][] }[] = [
  { href: "/check", label: "VeriFact", hint: "Check a post, link or screenshot", current: ["check", "report"] },
  { href: "/news", label: "NewsFact", hint: "Review a news story claim by claim", current: ["news", "newsWorkspace"] },
  { href: "/legal", label: "LegalFact", hint: "Find official legal sources", current: ["legal"] },
  { href: "/research", label: "ResearchFact", hint: "Check citations and research a topic", current: ["research", "researchWorkspace"] },
];

/**
 * Product links: inline on wide screens; on phones behind a menu button (the CSS decides which). The menu is
 * open only on the page where it was opened, so following a link closes it; Escape and tapping outside do too.
 */
export default function SiteNav({ route }: { route: Route["name"] }) {
  const pathname = usePathname();
  const { installed } = useInstall();
  const [openOn, setOpenOn] = useState<string | null>(null);
  const open = openOn === pathname;
  const navId = useId();
  const navRef = useRef<HTMLElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent) {
        if (event.key !== "Escape") return;
        setOpenOn(null);
        buttonRef.current?.focus();
        return;
      }
      const target = event.target as Node;
      if (!navRef.current?.contains(target) && !buttonRef.current?.contains(target)) setOpenOn(null);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  return (
    <>
      <nav id={navId} ref={navRef} className={open ? "site-nav is-open" : "site-nav"} aria-label="Products">
        {PRODUCTS.map((p) => (
          <Link key={p.href} href={p.href} aria-current={p.current.includes(route) ? "page" : undefined}>
            <span className="site-nav-label">{p.label}</span>
            <span className="site-nav-hint">{p.hint}</span>
          </Link>
        ))}
        <div className="site-nav-share">
          {!installed && (
            <Link href="/install" className="button button--primary button--small" aria-current={route === "install" ? "page" : undefined}>
              Install the app
            </Link>
          )}
          <ShareSite className="button button--secondary button--small" label="Share VeriFact with friends" />
        </div>
      </nav>
      <button
        ref={buttonRef}
        type="button"
        className="nav-toggle"
        aria-expanded={open}
        aria-controls={navId}
        aria-label={open ? "Close menu" : "Open menu"}
        onClick={() => setOpenOn(open ? null : pathname)}
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true" focusable="false">
          {open ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
        </svg>
      </button>
    </>
  );
}
