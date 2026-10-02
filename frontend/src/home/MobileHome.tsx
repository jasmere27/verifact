import { useEffect, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { getMyChecks } from "../auth/api";
import { shownName, useAuth } from "../auth/useAuth";
import Link from "../components/Link";
import VerdictBadge from "../components/VerdictBadge";
import { formatRelative } from "../format";
import { useInstall } from "../install";
import { getNewsWorkspace, savedReviewIds } from "../news/api";
import { listProjects } from "../research/projects/api";
import type { ProjectSummary } from "../research/projects/types";
import { getWorkspace, savedWorkspaceIds } from "../research/student/api";
import { loadRecent } from "../recent";
import { navigate, reportPath } from "../router";
import ShareSite from "../components/ShareSite";
import type { OverallVerdict } from "../types";
import "./home.css";

interface Recent {
  id: string;
  label: string;
  verdict: OverallVerdict;
  at: string;
}

interface Continue {
  href: string;
  title: string;
  meta: string;
  at: string;
  percent?: number;
}

const glyph = (d: ReactNode) => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    {d}
  </svg>
);

const GLYPHS = {
  research: glyph(
    <>
      <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z" />
      <path d="M4 20.5A2.5 2.5 0 0 0 6.5 23H20v-5M9 7h7M9 11h5" />
    </>,
  ),
  news: glyph(
    <>
      <path d="M4 5h13v14a2 2 0 0 0 2 2H6a2 2 0 0 1-2-2z" />
      <path d="M17 9h3v10a2 2 0 0 1-2 2M8 9h5M8 13h5M8 17h3" />
    </>,
  ),
  legal: glyph(
    <>
      <path d="M12 3v18M7 21h10M5 7h14" />
      <path d="M5 7l-3 6a3 3 0 0 0 6 0zM19 7l-3 6a3 3 0 0 0 6 0z" />
    </>,
  ),
  share: glyph(
    <>
      <circle cx="18" cy="5" r="2.5" />
      <circle cx="6" cy="12" r="2.5" />
      <circle cx="18" cy="19" r="2.5" />
      <path d="M8.2 10.8l7.6-4.4M8.2 13.2l7.6 4.4" />
    </>,
  ),
  image: glyph(
    <>
      <rect x="3" y="4" width="18" height="16" rx="3" />
      <circle cx="9" cy="10" r="1.8" />
      <path d="M21 16l-5-5-9 9" />
    </>,
  ),
  audio: glyph(
    <>
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
    </>,
  ),
  arrow: glyph(<path d="M5 12h14M13 6l6 6-6 6" />),
};

const QUICK: { href: string; label: string; hint: string; tone: string; icon: ReactNode }[] = [
  { href: "/research", label: "Research", hint: "Capstone & sources", tone: "research", icon: GLYPHS.research },
  { href: "/news", label: "Review a story", hint: "Claim by claim", tone: "news", icon: GLYPHS.news },
  { href: "/legal", label: "Legal sources", hint: "Official sources", tone: "legal", icon: GLYPHS.legal },
];

/**
 * Home on a phone: the student's own work first (quick actions, what to continue, recent checks) instead of
 * the marketing page, which stays at /about. Everything here comes from the account or this browser.
 */
export default function MobileHome() {
  const { session, account } = useAuth();
  const { installed } = useInstall();
  const userId = session?.user.id ?? null;
  const [now] = useState(() => Date.now());
  const [accountRecent, setAccountRecent] = useState<Recent[] | null>(null);
  const [accountProjects, setAccountProjects] = useState<ProjectSummary[]>([]);
  // Signed out, recent checks come from this browser (read once, during render).
  const [localRecent] = useState<Recent[]>(() =>
    loadRecent().slice(0, 5).map((c) => ({ id: c.id, label: c.label, verdict: c.overallVerdict, at: c.createdAt })),
  );
  const [work, setWork] = useState<Continue[]>([]);

  // Signed in: the account's check history and capstone projects.
  useEffect(() => {
    if (!userId) return;
    let active = true;
    getMyChecks().then(
      (items) =>
        active && setAccountRecent(items.slice(0, 5).map((c) => ({ id: c.id, label: c.label, verdict: c.overallVerdict as OverallVerdict, at: c.checkedAt }))),
      () => active && setAccountRecent([]),
    );
    listProjects().then((p) => active && setAccountProjects(p.slice(0, 3)), () => {});
    return () => {
      active = false;
    };
  }, [userId]);
  const recent = userId ? accountRecent : localRecent;
  const projects = userId ? accountProjects : [];

  // Workspaces and NewsFact reviews made in this browser.
  useEffect(() => {
    let active = true;
    const workspaces = savedWorkspaceIds().slice(0, 6).map((id) =>
      getWorkspace(id).then(
        (w): Continue => ({ href: `/research/w/${w.id}`, title: w.topic, meta: `Research workspace · ${w.sources.length} sources`, at: w.updatedAt }),
        () => null,
      ),
    );
    const reviews = savedReviewIds().slice(0, 6).map((id) =>
      getNewsWorkspace(id).then(
        (n): Continue => ({
          href: `/news/${n.check.id}`,
          title: n.check.articleTitle || n.check.claims[0]?.claim || "News review",
          meta: `NewsFact review · ${n.check.claims.length} claims`,
          at: n.review.updatedAt ?? n.check.createdAt,
        }),
        () => null,
      ),
    );
    Promise.all([...workspaces, ...reviews]).then((items) => {
      if (!active) return;
      setWork(items.filter((x): x is Continue => x !== null).sort((a, b) => b.at.localeCompare(a.at)).slice(0, 4));
    });
    return () => {
      active = false;
    };
  }, []);

  const continueItems: Continue[] = [
    ...projects.map((p) => ({
      href: `/research/p/${p.id}`,
      title: p.title,
      meta: `Capstone project · ${p.percent}% done`,
      at: p.updatedAt,
      percent: p.percent,
    })),
    ...work,
  ];
  const firstTime = recent !== null && recent.length === 0 && continueItems.length === 0;
  const name = session ? shownName(account, session) : null;
  const [query, setQuery] = useState("");

  function check(event: FormEvent) {
    event.preventDefault();
    const text = query.trim();
    navigate(text ? `/check?run=1&text=${encodeURIComponent(text)}` : "/check");
  }

  return (
    <div className="m-home">
      <section className="m-hero" aria-labelledby="m-hero-title">
        <p className="m-hero-kicker">{name ? `Hi, ${name}` : "Free · real sources · no sign-up"}</p>
        <h1 id="m-hero-title">Saw something viral? Check it first.</h1>
        <form className="m-search" onSubmit={check} role="search">
          <label htmlFor="m-search-input" className="visually-hidden">
            Paste a post or link to check
          </label>
          <input
            id="m-search-input"
            type="text"
            inputMode="text"
            enterKeyHint="go"
            autoComplete="off"
            placeholder="Paste a post or link…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <button type="submit" aria-label="Check it">
            {GLYPHS.arrow}
          </button>
        </form>
        <div className="m-hero-chips">
          <Link href="/check?mode=image" className="m-chip">
            {GLYPHS.image}
            Screenshot
          </Link>
          <Link href="/check?mode=audio" className="m-chip">
            {GLYPHS.audio}
            Audio clip
          </Link>
        </div>
      </section>

      <section aria-label="Quick actions">
        <ul className="m-quick">
          {QUICK.map((q) => (
            <li key={q.href}>
              <Link href={q.href} className={`m-quick-item m-quick-item--${q.tone}`}>
                <span className="m-quick-icon">{q.icon}</span>
                <strong>{q.label}</strong>
                <span>{q.hint}</span>
              </Link>
            </li>
          ))}
          <li>
            <span className={`m-quick-item m-quick-item--share`}>
              <span className="m-quick-icon">{GLYPHS.share}</span>
              <ShareSite className="m-quick-share" label="Share VeriFact" />
              <span>With classmates</span>
            </span>
          </li>
        </ul>
      </section>

      {continueItems.length > 0 && (
        <Section title="Continue">
          <ul className="m-carousel">
            {continueItems.map((c) => (
              <li key={c.href}>
                <Link href={c.href} className="m-card">
                  <span className="m-card-tag">{c.meta}</span>
                  <span className="m-card-title">{c.title}</span>
                  <span className="m-card-foot">
                    {c.percent !== undefined && (
                      <span className="pj-bar pj-bar--thin" aria-hidden="true">
                        <span style={{ width: `${c.percent}%` }} />
                      </span>
                    )}
                    <span className="m-row-meta">Updated {formatRelative(c.at, now)}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {recent && recent.length > 0 && (
        <Section title={userId ? "Your recent checks" : "Recent checks on this phone"} action={<Link href="/check">All</Link>}>
          <ul className="m-list">
            {recent.map((r) => (
              <li key={r.id}>
                <Link href={reportPath(r.id)} className={`m-row m-row--verdict v-${r.verdict.toLowerCase()}`}>
                  <span className="m-row-title">{r.label}</span>
                  <span className="m-row-meta m-row-meta--badge">
                    <VerdictBadge verdict={r.verdict} size="sm" /> {formatRelative(r.at, now)}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {firstTime && (
        <section className="m-intro">
          <p>
            VeriFact searches fact-checkers, news and reference sources and shows you what they say, with links, so you can
            decide for yourself.
          </p>
          <Link href="/about" className="button button--secondary button--small">
            How it works
          </Link>
        </section>
      )}

      {!installed && (
        <Link href="/install" className="m-install">
          <img src="/icon-192.png" width={40} height={40} alt="" />
          <span>
            <strong>Get the app</strong>
            <span className="muted small">Add VeriFact to your home screen. Free, no app store.</span>
          </span>
        </Link>
      )}
    </div>
  );
}

function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="m-section">
      <div className="m-section-head">
        <h2>{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}
