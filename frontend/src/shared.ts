/**
 * Text shared to VeriFact from another app (the installed app's share target, manifest.webmanifest): the
 * phone opens /check?title=…&text=…&url=…. Returns what to put in the check box, or null.
 */
export function sharedInput(search: string): string | null {
  const params = new URLSearchParams(search);
  const text = params.get("text")?.trim() ?? "";
  const title = params.get("title")?.trim() ?? "";
  const url = params.get("url")?.trim() ?? "";
  const body = text || title;
  // Apps often put the link inside the text too; don't repeat it.
  const parts = [body, url && !body.includes(url) ? url : ""].filter(Boolean);
  const combined = parts.join("\n\n");
  return combined ? combined.slice(0, 10_000) : null;
}

export type CheckMode = "text" | "image" | "audio";

/**
 * What /check was opened with: shared text (above), a starting tab (`?mode=image|audio`), and `?run=1` when the
 * person already pressed Check somewhere else (the Home search bar), so the check starts without a second tap.
 */
export interface CheckIntent {
  text: string | null;
  mode: CheckMode;
  run: boolean;
}

export function checkIntent(search: string): CheckIntent {
  const params = new URLSearchParams(search);
  const text = sharedInput(search);
  const m = params.get("mode");
  const mode: CheckMode = m === "image" || m === "audio" ? m : "text";
  return { text, mode, run: Boolean(text) && mode === "text" && params.get("run") === "1" };
}
