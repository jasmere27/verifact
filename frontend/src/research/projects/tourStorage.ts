const STORAGE_KEY = "vf.projectTour.v1";

/** Whether this browser has finished or skipped the project guide (a per-viewer convenience; may be unavailable). */
export function tourSeen(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === "done";
  } catch {
    return true; // storage blocked: don't show the guide on every visit
  }
}

export function markTourSeen() {
  try {
    localStorage.setItem(STORAGE_KEY, "done");
  } catch {
    // nothing to remember it in; fine
  }
}
