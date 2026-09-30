import type { FoundSource } from "./types";

/** "Ana M. Reyes" → "Reyes, A. M." (APA). Organisation names are kept as they are. */
function apaAuthor(name: string): string {
  if (name === "et al.") return "et al.";
  const parts = name.trim().split(/\s+/);
  if (parts.length < 2) return name;
  const family = parts[parts.length - 1];
  const initials = parts
    .slice(0, -1)
    .map((p) => `${p.replace(/\./g, "").charAt(0).toUpperCase()}.`)
    .join(" ");
  return `${family}, ${initials}`;
}

export function apa(s: FoundSource): string {
  const authors = s.authors.filter((a) => a !== "et al.").map(apaAuthor);
  const names =
    authors.length === 0
      ? ""
      : authors.length === 1
        ? authors[0]
        : `${authors.slice(0, -1).join(", ")}, & ${authors[authors.length - 1]}`;
  const etal = s.authors.includes("et al.") ? ", et al." : "";
  const year = s.year ? `(${s.year})` : "(n.d.)";
  const venue = s.venue ? ` ${s.venue}.` : "";
  const link = s.doi ? ` https://doi.org/${s.doi}` : s.url ? ` ${s.url}` : "";
  return `${names}${etal} ${year}. ${s.title}.${venue}${link}`.trim();
}
