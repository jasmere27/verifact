import { useEffect, useRef } from "react";
import type { ReactNode } from "react";
import Link from "../components/Link";
import VerdictIcon from "../components/VerdictIcon";
import "./landing.css";

/** Optional: set VITE_CONTACT_EMAIL at build time to show the "Request a pilot" email button. */
const CONTACT_EMAIL = (import.meta.env.VITE_CONTACT_EMAIL as string | undefined)?.trim() || "";

/** Adds `is-visible` to [data-reveal] elements as they scroll into view (CSS does the rest; reduced motion skips it). */
function useReveal() {
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = root.current;
    if (!el) return;
    const items = Array.from(el.querySelectorAll<HTMLElement>("[data-reveal]"));
    if (typeof IntersectionObserver === "undefined") {
      items.forEach((i) => i.classList.add("is-visible"));
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        }
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 },
    );
    items.forEach((i) => observer.observe(i));
    return () => observer.disconnect();
  }, []);
  return root;
}

type Product = {
  name: string;
  href: string;
  tagline: string;
  audience: string;
  checks: string[];
  cta: string;
  accent: string;
  icon: ReactNode;
};

const icon = (d: string) => (
  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    <path d={d} />
  </svg>
);

const PRODUCTS: Product[] = [
  {
    name: "VeriFact",
    href: "/check",
    tagline: "Check any claim, link or screenshot.",
    audience: "Anyone checking a viral post: readers, students, educators, moderators.",
    checks: ["Claims in text, links and screenshots", "A verdict per claim, with cited sources", "Shareable, evidence-first reports"],
    cta: "Check a claim",
    accent: "var(--accent)",
    icon: icon("M9 12.5l2 2 4-4.5M12 3l7.5 3v5.5c0 4.5-3.2 8.3-7.5 9.5-4.3-1.2-7.5-5-7.5-9.5V6L12 3z"),
  },
  {
    name: "NewsFact",
    href: "/news",
    tagline: "Fact-check a story before it runs.",
    audience: "Journalists, editors and fact-check desks.",
    checks: ["Facts, statistics, dates and attributions", "Quotes verified word for word", "Desk review workspace and report"],
    cta: "Check a story",
    accent: "var(--v-contradicted)",
    icon: icon("M4 5h13v14H6a2 2 0 01-2-2V5zm13 4h3v8a2 2 0 01-2 2M8 9h5M8 13h5M8 17h3"),
  },
  {
    name: "LegalFact",
    href: "/legal",
    tagline: "Case intelligence from a plain-language description.",
    audience: "Legal intake teams and professionals (information, not advice).",
    checks: ["Facts, timeline and jurisdiction, with quotes", "Missing information and inconsistencies", "Official statutes, regulations and guidance"],
    cta: "Try LegalFact",
    accent: "var(--v-misleading)",
    icon: icon("M12 3v18M5 7h14M7 7l-3 7a3 3 0 006 0L7 7zm10 0l-3 7a3 3 0 006 0l-3-7zM8 21h8"),
  },
  {
    name: "ResearchFact",
    href: "/research",
    tagline: "Check every citation before it's published.",
    audience: "Researchers, reviewers, journal editors and students.",
    checks: ["Fabricated, mismatched and retracted references", "Does the cited paper support the claim?", "Research that reports a different finding"],
    cta: "Check citations",
    accent: "var(--accent-2)",
    icon: icon("M6 4h9l3 3v13H6V4zm9 0v3h3M9 11h6M9 14h6M9 17h4"),
  },
];

const STEPS = [
  {
    title: "Find the claims",
    body: "The AI lists the specific, checkable statements in what you paste, and quotes your text so nothing is invented.",
  },
  {
    title: "Retrieve sources, outside the AI",
    body: "Our servers run the searches: the web, official government sites, or scholarly indexes. The model never browses on its own.",
  },
  {
    title: "Compare with the sources' own words",
    body: "Each verdict must cite a source actually retrieved and quote its words. Our code checks every citation and quote before you see it.",
  },
  {
    title: "Show the evidence, and what's uncertain",
    body: "You get the verdict, the quotes, the links, and the limitations: including \"not enough evidence\" when that's the honest answer.",
  },
];

const PRINCIPLES = [
  {
    title: "Evidence decides, not the model",
    body: "A verdict other than \"insufficient evidence\" needs a retrieved source. The AI isn't allowed to answer from memory.",
  },
  {
    title: "Citations are checked in code",
    body: "Every source ID must exist in what we retrieved; quotes must match the source word for word, or the claim is downgraded.",
  },
  {
    title: "Uncertainty is shown, not hidden",
    body: "\"Insufficient evidence\", \"needs review\" and \"lookup failed\" are real outcomes, and each report lists its limitations.",
  },
  {
    title: "Built-in guard against manipulation",
    body: "Pasted text and web pages are treated as data, never instructions, so hidden text can't steer the verdict.",
  },
  {
    title: "Authoritative sources where it matters",
    body: "LegalFact searches official federal and state sites only; ResearchFact uses Crossref, DataCite, OpenAlex and PubMed.",
  },
  {
    title: "A human makes the final call",
    body: "Reports are built for review: NewsFact has editor decisions, and every product says what to confirm before acting.",
  },
];

const USE_CASES = [
  {
    who: "Newsrooms",
    what: "Run a story through NewsFact before publishing: every figure, date and quote checked, with a desk review trail.",
    href: "/news",
  },
  {
    who: "Journals & researchers",
    what: "Catch fabricated or retracted references and claims the cited paper doesn't make, before peer review or submission.",
    href: "/research",
  },
  {
    who: "Legal teams",
    what: "Turn a messy client description into structured facts, a timeline, the questions to ask, and official sources to review.",
    href: "/legal",
  },
  {
    who: "Everyone else",
    what: "Paste a viral post or screenshot and see what fact-checkers, news and reference sources actually say.",
    href: "/check",
  },
];

const FAQ = [
  {
    q: "How does VeriFact avoid making things up?",
    a: "Our servers retrieve the sources, and the AI may only judge claims against them. Code then checks that every cited source was really retrieved and that quoted words appear in it. When the evidence doesn't settle a claim, the answer is \"insufficient evidence\", not a guess.",
  },
  {
    q: "Is LegalFact legal advice?",
    a: "No. LegalFact organises legal information and points to official sources for a professional to review. It never says whether someone has a case, and every report says so.",
  },
  {
    q: "Which sources do you use?",
    a: "VeriFact and NewsFact search the web (fact-checkers, news, reference and official sites). LegalFact searches official US federal and California government sites. ResearchFact uses Crossref, DataCite, OpenAlex (with Retraction Watch data) and PubMed.",
  },
  {
    q: "Is my text stored?",
    a: "VeriFact reports are saved so you can share their link, and NewsFact reviews are saved so your desk can work on them; anyone with the link can view them. LegalFact and ResearchFact store nothing. Text you submit is processed by our AI provider to run the check.",
  },
  {
    q: "Can it be wrong?",
    a: "Yes: search can miss sources, and abstracts or snippets don't contain everything. That's why every result shows its evidence and limitations, so you can judge it yourself and confirm important points in the original sources.",
  },
  {
    q: "Is it free? Do I need an account?",
    a: "All four products are free during early access, with fair-use limits and no sign-up. Team features and pricing are being shaped with our first pilot customers.",
  },
];

function Reveal({ children, className = "", as = "div", delay = 0 }: { children: ReactNode; className?: string; as?: "div" | "section" | "li"; delay?: number }) {
  const Tag = as;
  return (
    <Tag className={`reveal ${className}`} data-reveal style={delay ? { transitionDelay: `${delay}ms` } : undefined}>
      {children}
    </Tag>
  );
}

function HeroCard() {
  return (
    <div className="lp-hero-card" aria-label="Example report" role="img">
      <div className="lp-hero-card-head">
        <span className="lp-dot" />
        <span className="lp-dot" />
        <span className="lp-dot" />
        <span className="lp-hero-card-title">Example report</span>
      </div>
      <p className="lp-hero-claim">“The Eiffel Tower is located in Rome.”</p>
      <div className="lp-hero-verdict tone-contradicted">
        <VerdictIcon tone="contradicted" size={20} />
        <span>Contradicted</span>
        <span className="lp-strength" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      </div>
      <div className="lp-hero-source">
        <span className="lp-source-badge">Reference</span>
        <p>“The Eiffel Tower is a wrought-iron lattice tower on the Champ de Mars in Paris, France.”</p>
        <span className="lp-source-domain">encyclopedia source · retrieved today</span>
      </div>
      <div className="lp-hero-source lp-hero-source--second">
        <span className="lp-source-badge lp-source-badge--fc">Fact-checker</span>
        <p>“Claims placing the tower outside Paris are false.”</p>
        <span className="lp-source-domain">fact-check source · retrieved today</span>
      </div>
    </div>
  );
}

export default function Landing() {
  const root = useReveal();
  return (
    <div className="landing" ref={root}>
      {/* Hero */}
      <section className="lp-hero" aria-labelledby="lp-hero-heading">
        <div className="lp-hero-copy">
          <p className="lp-eyebrow reveal is-visible">Evidence intelligence · Early access</p>
          <h1 id="lp-hero-heading" className="lp-hero-title">
            Answers you can <span className="headline-accent">check.</span>
          </h1>
          <p className="lp-hero-lede">
            VeriFact verifies claims against real sources and shows its work: every verdict comes with the evidence,
            the quotes and the links, and says plainly what it couldn&apos;t confirm.
          </p>
          <div className="lp-hero-ctas">
            <Link href="/check" className="button button--primary lp-cta">
              Check a claim, free
            </Link>
            <a href="#products" className="button button--secondary lp-cta">
              Explore the products
            </a>
          </div>
          <p className="lp-hero-note">No sign-up. Four products for news, law, research and everyday claims.</p>
        </div>
        <HeroCard />
      </section>

      {/* Problem strip */}
      <Reveal as="section" className="lp-problem">
        <p>
          AI can write a confident answer in seconds, and so can anyone posting online. <strong>What&apos;s missing is the
          evidence.</strong> VeriFact is built the other way round: sources first, then a verdict you can trace back to
          them.
        </p>
      </Reveal>

      {/* Products */}
      <section id="products" className="lp-section" aria-labelledby="lp-products-heading">
        <Reveal className="lp-section-head">
          <p className="lp-kicker">Products</p>
          <h2 id="lp-products-heading">One evidence engine. Four specialist tools.</h2>
          <p className="lp-section-lede">Each product has its own workflow and sources, and shares the same rule: no claim without evidence.</p>
        </Reveal>
        <ul className="lp-products">
          {PRODUCTS.map((p, i) => (
            <Reveal as="li" key={p.name} className="lp-product" delay={i * 70}>
              <div className="lp-product-icon" style={{ color: p.accent }}>
                {p.icon}
              </div>
              <h3>{p.name}</h3>
              <p className="lp-product-tagline">{p.tagline}</p>
              <p className="lp-product-audience">{p.audience}</p>
              <ul className="lp-product-checks">
                {p.checks.map((c) => (
                  <li key={c}>{c}</li>
                ))}
              </ul>
              <Link href={p.href} className="lp-product-cta">
                {p.cta} <span aria-hidden="true">→</span>
              </Link>
            </Reveal>
          ))}
        </ul>
      </section>

      {/* How it works */}
      <section id="how" className="lp-section lp-section--tinted" aria-labelledby="lp-how-heading">
        <Reveal className="lp-section-head">
          <p className="lp-kicker">How it works</p>
          <h2 id="lp-how-heading">Sources first. Verdict second. You decide.</h2>
        </Reveal>
        <ol className="lp-steps">
          {STEPS.map((s, i) => (
            <Reveal as="li" key={s.title} className="lp-step" delay={i * 80}>
              <span className="lp-step-num" aria-hidden="true">
                {i + 1}
              </span>
              <h3>{s.title}</h3>
              <p>{s.body}</p>
            </Reveal>
          ))}
        </ol>
      </section>

      {/* Evidence / trust */}
      <section id="trust" className="lp-section" aria-labelledby="lp-trust-heading">
        <Reveal className="lp-section-head">
          <p className="lp-kicker">Evidence &amp; trust</p>
          <h2 id="lp-trust-heading">Designed to show its work.</h2>
          <p className="lp-section-lede">Trust shouldn&apos;t depend on a confident tone. These rules are enforced in code, not just in a prompt.</p>
        </Reveal>
        <ul className="lp-principles">
          {PRINCIPLES.map((p, i) => (
            <Reveal as="li" key={p.title} className="lp-principle" delay={(i % 3) * 70}>
              <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false" className="lp-check">
                <path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              <div>
                <h3>{p.title}</h3>
                <p>{p.body}</p>
              </div>
            </Reveal>
          ))}
        </ul>
      </section>

      {/* Use cases */}
      <section id="use-cases" className="lp-section lp-section--tinted" aria-labelledby="lp-uses-heading">
        <Reveal className="lp-section-head">
          <p className="lp-kicker">Use cases</p>
          <h2 id="lp-uses-heading">Built for people whose work depends on getting it right.</h2>
        </Reveal>
        <ul className="lp-uses">
          {USE_CASES.map((u, i) => (
            <Reveal as="li" key={u.who} className="lp-use" delay={i * 70}>
              <h3>{u.who}</h3>
              <p>{u.what}</p>
              <Link href={u.href} className="lp-product-cta">
                Try it <span aria-hidden="true">→</span>
              </Link>
            </Reveal>
          ))}
        </ul>
      </section>

      {/* Pricing */}
      <section id="pricing" className="lp-section" aria-labelledby="lp-pricing-heading">
        <Reveal className="lp-section-head">
          <p className="lp-kicker">Pricing</p>
          <h2 id="lp-pricing-heading">Free while in early access.</h2>
          <p className="lp-section-lede">Team plans are being shaped with our first pilot customers; we won&apos;t charge for anything that isn&apos;t proven useful.</p>
        </Reveal>
        <div className="lp-plans">
          <Reveal className="lp-plan lp-plan--featured">
            <p className="lp-plan-name">Early access</p>
            <p className="lp-plan-price">
              $0 <span>/ no sign-up</span>
            </p>
            <ul>
              <li>All four products</li>
              <li>Shareable reports and NewsFact review workspaces</li>
              <li>Fair use: 5 checks a minute, 50 a day</li>
            </ul>
            <Link href="/check" className="button button--primary lp-plan-cta">
              Start checking
            </Link>
          </Reveal>
          <Reveal className="lp-plan" delay={80}>
            <p className="lp-plan-name">Team pilot</p>
            <p className="lp-plan-price">
              Custom <span>/ by arrangement</span>
            </p>
            <ul>
              <li>For newsrooms, journals and legal content teams</li>
              <li>Higher limits and batch checks (e.g. content audits)</li>
              <li>Onboarding and a direct line for feedback</li>
            </ul>
            {CONTACT_EMAIL ? (
              <a href={`mailto:${CONTACT_EMAIL}?subject=VeriFact%20pilot`} className="button button--secondary lp-plan-cta">
                Request a pilot
              </a>
            ) : (
              <span className="lp-plan-soon">Pilot requests open soon</span>
            )}
          </Reveal>
          <Reveal className="lp-plan" delay={160}>
            <p className="lp-plan-name">API</p>
            <p className="lp-plan-price">
              Soon <span>/ in planning</span>
            </p>
            <ul>
              <li>Claim, citation and story checks from your own tools</li>
              <li>The same evidence-first results as the apps</li>
              <li>Usage-based, once validated with pilots</li>
            </ul>
            <span className="lp-plan-soon">Not available yet</span>
          </Reveal>
        </div>
      </section>

      {/* FAQ */}
      <section id="faq" className="lp-section lp-section--tinted" aria-labelledby="lp-faq-heading">
        <Reveal className="lp-section-head">
          <p className="lp-kicker">FAQ</p>
          <h2 id="lp-faq-heading">Questions, answered plainly.</h2>
        </Reveal>
        <div className="lp-faq">
          {FAQ.map((f) => (
            <details key={f.q} className="lp-faq-item">
              <summary>{f.q}</summary>
              <p>{f.a}</p>
            </details>
          ))}
        </div>
      </section>

      {/* Final CTA */}
      <Reveal as="section" className="lp-final">
        <h2>See the evidence for yourself.</h2>
        <p>Paste a claim, a story, a case description or a reference list. It&apos;s free, and there&apos;s nothing to sign up for.</p>
        <div className="lp-hero-ctas lp-final-ctas">
          <Link href="/check" className="button button--primary lp-cta">
            Check a claim
          </Link>
          <Link href="/news" className="button button--secondary lp-cta">
            Check a story
          </Link>
        </div>
      </Reveal>
    </div>
  );
}
