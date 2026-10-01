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

/** On sign-out: the next person using this browser can't delete (or find) the reports made here. */
export function forgetAllReportTokens() {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key?.startsWith("verifact.token.")) keys.push(key);
    }
    keys.forEach((key) => localStorage.removeItem(key));
  } catch {
    // ignore
  }
}
