/**
 * Edit tokens for VeriFact reports: a random token sent with each check and kept in this browser
 * only for reports the check created, so this browser (and nobody else) can delete them.
 */
const KEY = (id: string) => `verifact.token.${id}`;

export function newEditToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function reportToken(id: string): string | null {
  try {
    return localStorage.getItem(KEY(id));
  } catch {
    return null;
  }
}

export function saveReportToken(id: string, token: string) {
  try {
    localStorage.setItem(KEY(id), token);
  } catch {
    // Storage blocked: the report just can't be deleted from this browser.
  }
}

export function forgetReportToken(id: string) {
  try {
    localStorage.removeItem(KEY(id));
  } catch {
    // ignore
  }
}
