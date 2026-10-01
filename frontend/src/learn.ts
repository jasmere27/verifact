import { domainOf, toSourceType } from "./sources";
import type { Evidence, VerificationResult } from "./types";

/**
 * "Check it yourself": warning signs and fact-checking steps for learners, worked out in code from the
 * submission and the report's evidence (no model call, so no extra cost and nothing the model invents).
 * Warning signs are reasons to slow down, never proof: the verdict comes from the evidence.
 */
export interface WarningSign {
  id: string;
  title: string;
  why: string;
}

export interface Step {
  id: string;
  text: string;
  /** Show the reverse image search links with this step. */
  imageSearch?: boolean;
}

export interface Lesson {
  signs: WarningSign[];
  steps: Step[];
}

/** Pressure to share or to distrust everyone else, in English and Tagalog/Taglish (lower case). */
const PRESSURE_PHRASES = [
  "share before",
  "before it's deleted",
  "before it gets deleted",
  "before they delete",
  "they don't want you to know",
  "the media won't tell you",
  "spread the word",
  "forward this",
  "pass it on",
  "share this now",
  "share now",
  "must share",
  "100% true",
  "100% totoo",
  "i-share",
  "ishare",
  "pakishare",
  "paki-share",
  "share mo",
  "ibahagi",
  "ipasa",
  "bago ma-delete",
  "bago burahin",
  "hindi sasabihin ng media",
  "totoo ito",
];

const ALARM_WORDS = ["breaking", "urgent", "warning", "alert", "shocking", "exposed", "babala", "agad", "grabe"];

const YEAR_MS = 365 * 86_400_000;

function textOf(result: VerificationResult): string {
  // For images and audio the input is a file name; what was read or heard is the checked text.
  const parts = result.inputType === "TEXT" ? [result.input] : [result.checkedText ?? ""];
  return parts.join("\n");
}

function shouting(text: string): boolean {
  const letters = text.replace(/[^A-Za-z]/g, "");
  if (letters.length < 20) return false;
  const upper = letters.replace(/[^A-Z]/g, "").length;
  return upper / letters.length > 0.5;
}

function hasLink(text: string): boolean {
  return /https?:\/\/|www\.|\b[a-z0-9-]+\.(com|org|net|gov|ph|edu|news|info)\b/i.test(text);
}

function dated(evidence: Evidence[]): number[] {
  return evidence.map((e) => (e.publishedDate ? Date.parse(e.publishedDate) : Number.NaN)).filter((t) => !Number.isNaN(t));
}

export function lessonFor(result: VerificationResult): Lesson {
  const text = textOf(result);
  const lower = text.toLowerCase();
  const signs: WarningSign[] = [];

  if (shouting(text)) {
    signs.push({
      id: "caps",
      title: "Lots of CAPITAL letters",
      why: "Writing in capitals is meant to make you feel alarmed. Strong feelings are a reason to slow down before believing or sharing.",
    });
  }
  const exclamations = (text.match(/!/g) ?? []).length;
  const alarm = ALARM_WORDS.find((w) => new RegExp(`\\b${w}\\b`, "i").test(text));
  if (exclamations >= 3 || alarm) {
    signs.push({
      id: "alarm",
      title: alarm ? `Alarm words like “${alarm}”` : "Many exclamation marks",
      why: "Posts that try to make you panic or angry spread faster than careful ones. Reliable reports usually sound calm.",
    });
  }
  const pressure = PRESSURE_PHRASES.find((p) => lower.includes(p));
  if (pressure) {
    signs.push({
      id: "pressure",
      title: `Pressure to share (“${pressure}”)`,
      why: "Real news doesn't need you to share it before it disappears. Rushing you is a common trick in false posts.",
    });
  }
  if (result.inputType === "TEXT" && !hasLink(text)) {
    signs.push({
      id: "nosource",
      title: "No link to where it came from",
      why: "Without the original source you can't see who said it, when, or in what context. Try to find the original before trusting it.",
    });
  }
  if (result.inputType === "IMAGE") {
    signs.push({
      id: "screenshot",
      title: "It's a screenshot or image",
      why: "Screenshots are easy to edit, and real photos are often reused with a new, false story. Look for where the image first appeared.",
    });
  }
  const conflict = result.claims.some((c) => c.supportingEvidenceIds.length > 0 && c.contradictingEvidenceIds.length > 0);
  if (conflict) {
    signs.push({
      id: "conflict",
      title: "Sources disagree",
      why: "Some sources support part of this and others contradict it. Read both sides and check which sources are more reliable and more recent.",
    });
  }
  const created = Date.parse(result.createdAt);
  const times = dated(result.evidence);
  if (!Number.isNaN(created) && times.length >= 2 && times.filter((t) => created - t > YEAR_MS).length * 2 > times.length) {
    signs.push({
      id: "old",
      title: "The sources are mostly over a year old",
      why: "Old stories often come back as if they were new. Check whether this is old news being shared again.",
    });
  }
  const nonSocial = result.evidence.filter((e) => toSourceType(e.sourceType) !== "SOCIAL");
  if (result.overallVerdict === "INSUFFICIENT_EVIDENCE" || nonSocial.length === 0) {
    signs.push({
      id: "thin",
      title: "Few reliable sources mention it",
      why: "Big, true news is usually reported by several independent outlets. If only posts and unknown sites have it, wait before believing it.",
    });
  }

  const steps: Step[] = [];
  if (signs.some((s) => s.id === "caps" || s.id === "alarm" || s.id === "pressure")) {
    steps.push({ id: "pause", text: "Pause. Notice how the post makes you feel before deciding whether it's true." });
  }
  if (result.inputType === "URL") {
    const host = hostOf(result.input);
    steps.push({
      id: "who",
      text: host
        ? `Check who published it: search for “${host}” to learn who runs the site and whether it's known for accurate reporting.`
        : "Check who published it and whether they're known for accurate reporting.",
    });
  } else if (result.inputType === "IMAGE") {
    steps.push({
      id: "image",
      text: "Search the image itself to see where and when it first appeared. Upload the same screenshot or photo:",
      imageSearch: true,
    });
  } else {
    steps.push({
      id: "origin",
      text: "Find the original: search a distinctive phrase from the post in quotation marks to see where it started.",
    });
  }
  const factCheck = result.evidence.find((e) => toSourceType(e.sourceType) === "FACT_CHECKER");
  if (factCheck) {
    steps.push({ id: "factcheck", text: `Fact-checkers have looked at this. Read what ${domainOf(factCheck)} found (in the sources above).` });
  }
  const outlets = new Set(nonSocial.map((e) => domainOf(e))).size;
  steps.push({
    id: "lateral",
    text:
      outlets >= 3
        ? `Read across sources: open at least two of the ${outlets} different publishers above, not just the first one.`
        : outlets === 2
          ? "Read across sources: open both publishers above, not just the first one."
          : "Read across sources: look for at least two independent, reliable publishers reporting the same thing.",
  });
  steps.push({ id: "date", text: "Check the dates: is the story recent, or an old one shared again?" });
  steps.push({ id: "decide", text: decideStep(result.overallVerdict) });

  return { signs, steps };
}

function hostOf(url: string): string | null {
  try {
    return new URL(url.trim()).hostname.replace(/^www\./, "");
  } catch {
    return null;
  }
}

function decideStep(verdict: VerificationResult["overallVerdict"]): string {
  switch (verdict) {
    case "CONTRADICTED":
    case "MISLEADING":
      return "Don't share it. If a friend did, reply kindly with a reliable source (the “Copy reply” button helps).";
    case "SUPPORTED":
      return "If you share it, link the original reliable source instead of a screenshot.";
    case "PARTLY_SUPPORTED":
    case "MIXED":
      return "If you share it, say which parts are true and which aren't, with a source.";
    default:
      return "Wait for reliable reporting before believing or sharing it.";
  }
}

/** Reverse image search sites. VeriFact doesn't keep uploaded images, so the student uploads it there. */
export const IMAGE_SEARCH = [
  { name: "Google Lens", href: "https://lens.google.com/" },
  { name: "TinEye", href: "https://tineye.com/" },
  { name: "Bing Visual Search", href: "https://www.bing.com/visualsearch" },
];
