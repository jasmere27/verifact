import type { Route } from "../router";
import Link from "./Link";

const PRODUCTS: { href: string; label: string; current: Route["name"][] }[] = [
  { href: "/check", label: "VeriFact", current: ["check", "report"] },
  { href: "/news", label: "NewsFact", current: ["news", "newsWorkspace"] },
  { href: "/legal", label: "LegalFact", current: ["legal"] },
  { href: "/research", label: "ResearchFact", current: ["research", "researchWorkspace", "researchProject"] },
];

/** Product links in the header on tablets and desktops; phones use the bottom tab bar (BottomNav). */
export default function SiteNav({ route }: { route: Route["name"] }) {
  return (
    <nav className="site-nav" aria-label="Products">
      {PRODUCTS.map((p) => (
        <Link key={p.href} href={p.href} aria-current={p.current.includes(route) ? "page" : undefined}>
          {p.label}
        </Link>
      ))}
    </nav>
  );
}
