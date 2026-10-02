import { goBack } from "../router";
import type { Route } from "../router";

/**
 * Back arrow for the installed app on phones: it has no browser toolbar, and on iPhone no system Back, so a page
 * below the tab bar's pages would otherwise be a dead end.
 */
export default function BackButton({ route }: { route: Route["name"] }) {
  return (
    <button type="button" className="header-back" onClick={() => goBack(route)} aria-label="Back">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M15 5l-7 7 7 7" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </button>
  );
}
