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
